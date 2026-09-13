import { createApp } from 'vue'
import ElementPlus from 'element-plus'

import 'element-plus/dist/index.css'
import '@/assets/base.css'

import { setUnauthorizedHandler } from '@/api/http'
import App from '@/App.vue'
import router from '@/router'
import { pinia } from '@/stores'
import { useAuthStore } from '@/stores/auth'

const authStore = useAuthStore(pinia)
setUnauthorizedHandler(() => {
  const currentRoute = router.currentRoute.value
  authStore.clearSession()
  if (currentRoute.meta.requiresAuth) {
    void router.replace({ name: 'login', query: { redirect: currentRoute.fullPath } })
  }
})

createApp(App).use(pinia).use(router).use(ElementPlus).mount('#app')
