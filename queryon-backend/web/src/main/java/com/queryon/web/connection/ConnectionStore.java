package com.queryon.web.connection;

import com.queryon.common.audit.AuditEvent;
import com.queryon.common.audit.AuditLog;
import com.queryon.common.error.ErrorCode;
import com.queryon.common.error.QueryonException;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 连接管理（`D1-01`、`D1-02` 社区版部分）：连接参数可配置、连通性可测试、启停可控。
 *
 * <p>凭据只以进程内 AES-GCM 密文保存，任何接口都不回传明文、不明文落盘；
 * 连通性测试只做 TCP 可达性探测，不使用也不校验用户口令。
 * 内置示例数据集连接不可删除，避免演示与回归失去取数入口。
 */
@Component
public class ConnectionStore {

    /** 对外视图：不含密码字段，只暴露是否已设置凭据。 */
    public record View(String id, String name, String type, String host, Integer port,
                       String database, String username, boolean readOnly, boolean enabled,
                       boolean hasCredential, String status, String statusDetail,
                       String lastTestedAt, long latencyMs) {
    }

    /** 新增／编辑入参：密码留空表示保持原凭据不变。 */
    public record Input(String name, String type, String host, Integer port, String database,
                        String username, String password, Boolean readOnly, Boolean enabled) {
    }

    public record ProbeResult(boolean reachable, String status, String detail, long latencyMs) {
    }

    private record Stored(String id, String name, String type, String host, Integer port,
                          String database, String username, boolean readOnly, boolean enabled,
                          byte[] credential, String status, String statusDetail,
                          String lastTestedAt, long latencyMs) {
    }

    private static final Set<String> SUPPORTED_TYPES = new LinkedHashSet<>(List.of(
            "POSTGRESQL", "MYSQL", "CLICKHOUSE", "CSV", "API", "IN_MEMORY_SAMPLE"));

    /** 需要做网络可达性探测的类型；文件与 API 类型只做配置校验。 */
    private static final Set<String> NETWORK_TYPES = Set.of("POSTGRESQL", "MYSQL", "CLICKHOUSE");

    private static final String SAMPLE_ID = "conn-sample";
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Map<String, Stored> connections = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private final CredentialCipher cipher = new CredentialCipher();
    private final AuditLog auditLog;

    public ConnectionStore(AuditLog auditLog) {
        this.auditLog = auditLog;
        Stored sample = new Stored(SAMPLE_ID, "内置电商示例数据集", "IN_MEMORY_SAMPLE",
                "", null, "", "", true, true, null,
                "READY", "内置示例数据集，无需网络连接", "", 0);
        connections.put(SAMPLE_ID, sample);
    }

    public List<View> list() {
        return connections.values().stream()
                .sorted(Comparator.comparing(Stored::id))
                .map(this::view)
                .toList();
    }

    public View create(Input input, String actorId) {
        Input safe = validate(input);
        String id = "conn-" + sequence.incrementAndGet();
        Stored stored = new Stored(id, safe.name(), safe.type(), safe.host(), safe.port(),
                orEmpty(safe.database()), orEmpty(safe.username()),
                Boolean.TRUE.equals(safe.readOnly()), !Boolean.FALSE.equals(safe.enabled()),
                cipher.encrypt(safe.password()), "UNTESTED", "尚未测试连接", "", 0);
        connections.put(id, stored);
        audit(actorId, "连接管理·新增", "成功", id + " " + stored.type());
        return view(stored);
    }

    public View update(String id, Input input, String actorId) {
        Stored existing = require(id);
        Input safe = validate(input);
        byte[] credential = safe.password() == null || safe.password().isBlank()
                ? existing.credential()
                : cipher.encrypt(safe.password());
        Stored updated = new Stored(existing.id(), safe.name(), safe.type(), safe.host(), safe.port(),
                orEmpty(safe.database()), orEmpty(safe.username()),
                Boolean.TRUE.equals(safe.readOnly()), !Boolean.FALSE.equals(safe.enabled()),
                credential, existing.status(), existing.statusDetail(),
                existing.lastTestedAt(), existing.latencyMs());
        connections.put(id, updated);
        audit(actorId, "连接管理·编辑", "成功", id + " " + updated.type());
        return view(updated);
    }

    /** 连通性测试：TCP 可达性探测，超时 1.2 秒，失败原因可读且不含凭据。 */
    public View test(String id, String actorId) {
        Stored existing = require(id);
        ProbeResult probe = probe(existing);
        String now = LocalDateTime.now().format(TIME_FORMAT);
        Stored updated = new Stored(existing.id(), existing.name(), existing.type(), existing.host(),
                existing.port(), existing.database(), existing.username(), existing.readOnly(),
                existing.enabled(), existing.credential(), probe.status(), probe.detail(),
                now, probe.latencyMs());
        connections.put(id, updated);
        audit(actorId, "连接管理·测试连接", probe.reachable() ? "成功" : "拦截",
                id + " " + probe.status());
        return view(updated);
    }

    public View toggle(String id, boolean enabled, String actorId) {
        Stored existing = require(id);
        Stored updated = new Stored(existing.id(), existing.name(), existing.type(), existing.host(),
                existing.port(), existing.database(), existing.username(), existing.readOnly(),
                enabled, existing.credential(), existing.status(), existing.statusDetail(),
                existing.lastTestedAt(), existing.latencyMs());
        connections.put(id, updated);
        audit(actorId, enabled ? "连接管理·启用" : "连接管理·停用", "成功", id);
        return view(updated);
    }

    public void delete(String id, String actorId) {
        require(id);
        if (SAMPLE_ID.equals(id)) {
            throw new QueryonException(ErrorCode.PERMISSION_DENIED, "连接管理",
                    "内置示例数据集连接不可删除", "如需替换数据源，请新增连接并停用内置连接");
        }
        connections.remove(id);
        audit(actorId, "连接管理·删除", "成功", id);
    }

    private ProbeResult probe(Stored stored) {
        if ("IN_MEMORY_SAMPLE".equals(stored.type())) {
            return new ProbeResult(true, "READY", "内置示例数据集，无需网络连接", 0);
        }
        if (!NETWORK_TYPES.contains(stored.type())) {
            return new ProbeResult(true, "READY",
                    "文件／API 类型不做网络探测，连接参数已通过校验", 0);
        }
        if (stored.host() == null || stored.host().isBlank() || stored.port() == null) {
            return new ProbeResult(false, "UNREACHABLE", "缺少主机或端口，无法探测", 0);
        }
        long start = System.nanoTime();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(stored.host(), stored.port()), 1200);
            long elapsed = (System.nanoTime() - start) / 1_000_000;
            return new ProbeResult(true, "READY", "端口可达，连接可用", elapsed);
        } catch (IOException | RuntimeException exception) {
            long elapsed = (System.nanoTime() - start) / 1_000_000;
            String reason = exception.getMessage() == null ? exception.getClass().getSimpleName()
                    : exception.getMessage();
            String safeReason = reason.length() > 120 ? reason.substring(0, 120) : reason;
            return new ProbeResult(false, "UNREACHABLE",
                    "无法连接：" + safeReason + "（请检查主机、端口与网络策略）", elapsed);
        }
    }

    private Input validate(Input input) {
        if (input == null) {
            throw invalid("缺少连接参数", "请提交 name、type、host、port 等字段");
        }
        String name = orEmpty(input.name()).trim();
        if (name.isEmpty()) {
            throw invalid("连接名称不能为空", "请填写便于识别的连接名称");
        }
        String type = orEmpty(input.type()).trim().toUpperCase();
        if (!SUPPORTED_TYPES.contains(type)) {
            throw invalid("不支持的连接类型：" + type,
                    "可选类型：" + String.join("／", SUPPORTED_TYPES));
        }
        String host = orEmpty(input.host()).trim();
        Integer port = input.port();
        if (NETWORK_TYPES.contains(type)) {
            if (host.isEmpty()) {
                throw invalid("主机地址不能为空", "请填写可访问的主机名或 IP");
            }
            if (port == null || port < 1 || port > 65535) {
                throw invalid("端口不合法", "端口范围 1～65535，例如 PostgreSQL 默认 5432");
            }
        } else if (port != null && (port < 1 || port > 65535)) {
            throw invalid("端口不合法", "端口范围 1～65535");
        }
        return new Input(name, type, host, port, orEmpty(input.database()).trim(),
                orEmpty(input.username()).trim(), input.password(),
                input.readOnly(), input.enabled());
    }

    private QueryonException invalid(String reason, String hint) {
        return new QueryonException(ErrorCode.PARSE_GATE_REJECT, "连接管理", reason, hint);
    }

    private Stored require(String id) {
        Stored stored = connections.get(id);
        if (stored == null) {
            throw new QueryonException(ErrorCode.SCHEMA_GATE_REJECT, "连接管理",
                    "连接不存在或已删除", "请刷新连接列表后重试");
        }
        return stored;
    }

    private View view(Stored stored) {
        return new View(stored.id(), stored.name(), stored.type(), stored.host(), stored.port(),
                stored.database(), stored.username(), stored.readOnly(), stored.enabled(),
                stored.credential() != null && stored.credential().length > 0,
                stored.status(), stored.statusDetail(), stored.lastTestedAt(), stored.latencyMs());
    }

    private void audit(String actorId, String action, String outcome, String detail) {
        String traceId = "conn-" + UUID.randomUUID().toString().substring(0, 8);
        auditLog.record(new AuditEvent(traceId, actorId, "连接管理", action, outcome, detail));
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * 进程内凭据密文：AES-GCM + 每次随机 IV，密钥随进程生成。
     * 不写文件、不返回明文；进程重启后旧密文自然失效，属于骨架阶段的预期取舍。
     */
    private static final class CredentialCipher {

        private static final int IV_LENGTH = 12;
        private static final int TAG_BITS = 128;

        private final SecretKey key;
        private final SecureRandom random = new SecureRandom();

        private CredentialCipher() {
            try {
                KeyGenerator generator = KeyGenerator.getInstance("AES");
                generator.init(256);
                this.key = generator.generateKey();
            } catch (GeneralSecurityException exception) {
                throw new IllegalStateException("无法初始化连接凭据加密组件", exception);
            }
        }

        /** 密码为空返回 null，表示该连接没有设置凭据。 */
        private byte[] encrypt(String plain) {
            if (plain == null || plain.isEmpty()) {
                return null;
            }
            try {
                byte[] iv = new byte[IV_LENGTH];
                random.nextBytes(iv);
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
                byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
                byte[] result = new byte[iv.length + encrypted.length];
                System.arraycopy(iv, 0, result, 0, iv.length);
                System.arraycopy(encrypted, 0, result, iv.length, encrypted.length);
                return result;
            } catch (GeneralSecurityException exception) {
                throw new IllegalStateException("连接凭据加密失败", exception);
            }
        }
    }
}
