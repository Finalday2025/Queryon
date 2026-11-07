import { ref } from 'vue'

const userId = ref<string>(localStorage.getItem('queryon-user') || 'admin_bi')
const sessionId = ref<string>('session-' + Math.random().toString(36).slice(2, 10))

export function useSession() {
  function setUser(id: string) {
    userId.value = id
    localStorage.setItem('queryon-user', id)
  }
  return { userId, sessionId, setUser }
}
