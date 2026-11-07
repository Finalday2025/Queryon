import * as echarts from 'echarts'
import type { ResultSet } from '../types'

/** 社区版多格式导出（D3-07）：CSV／Excel／图片／PDF 打印，零新增依赖。 */
function download(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.click()
  URL.revokeObjectURL(url)
}

function cellText(value: unknown): string {
  if (value == null) return ''
  const s = String(value)
  return /[",\n]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s
}

export function exportCsv(rs: ResultSet, headers: string[], filename: string) {
  const lines = [headers.join(',')]
  rs.rows.forEach((row) => lines.push(rs.columns.map((c) => cellText(row[c])).join(',')))
  download(new Blob(['\uFEFF' + lines.join('\n')], { type: 'text/csv;charset=utf-8' }), filename + '.csv')
}

/** SpreadsheetML 2003：纯文本 XML，Excel 可直接打开，不引入 POI 等依赖。 */
export function exportExcel(rs: ResultSet, headers: string[], filename: string) {
  const esc = (s: string) =>
    s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')
  const rows = [
    '<Row>' + headers.map((h) => `<Cell><Data ss:Type="String">${esc(h)}</Data></Cell>`).join('') + '</Row>',
    ...rs.rows.map(
      (row) =>
        '<Row>' +
        rs.columns
          .map((c) => {
            const v = row[c]
            const numeric = typeof v === 'number'
            return `<Cell><Data ss:Type="${numeric ? 'Number' : 'String'}">${esc(String(v ?? ''))}</Data></Cell>`
          })
          .join('') +
        '</Row>'
    )
  ]
  const xml =
    '<?xml version="1.0"?><?mso-application progid="Excel.Sheet"?>' +
    '<Workbook xmlns="urn:schemas-microsoft-com:office:spreadsheet" ' +
    'xmlns:ss="urn:schemas-microsoft-com:office:spreadsheet">' +
    '<Worksheet ss:Name="Queryon"><Table>' +
    rows.join('') +
    '</Table></Worksheet></Workbook>'
  download(new Blob([xml], { type: 'application/vnd.ms-excel;charset=utf-8' }), filename + '.xls')
}

/** 从 ECharts 容器导出 PNG。 */
export function exportChartPng(container: HTMLElement | null | undefined, filename: string) {
  if (!container) return
  const instance = echarts.getInstanceByDom(container)
  if (!instance) return
  const dataUrl = instance.getDataURL({ type: 'png', pixelRatio: 2, backgroundColor: '#ffffff' })
  const a = document.createElement('a')
  a.href = dataUrl
  a.download = filename + '.png'
  a.click()
}

/** PDF 交由浏览器打印对话框「另存为 PDF」，避免引入 PDF 生成依赖。 */
export function exportPdf(html: string, title: string) {
  const w = window.open('', '_blank')
  if (!w) return
  w.document.write(
    '<!DOCTYPE html><html><head><meta charset="utf-8"><title>' +
      title +
      '</title></head><body>' +
      html +
      '</body></html>'
  )
  w.document.close()
  w.focus()
  w.print()
}
