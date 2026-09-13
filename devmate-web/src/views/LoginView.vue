<script setup lang="ts">
import type { FormInstance, FormRules } from 'element-plus'
import { computed, reactive, ref } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'

import { getErrorMessage } from '@/api/errors'
import { useAuthStore } from '@/stores/auth'

interface LoginForm {
  username: string
  password: string
}

const formRef = ref<FormInstance>()
const form = reactive<LoginForm>({ username: '', password: '' })
const errorMessage = ref('')
const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()

const rules: FormRules<LoginForm> = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { max: 50, message: '用户名不能超过 50 个字符', trigger: 'blur' },
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { max: 72, message: '密码不能超过 72 个字符', trigger: 'blur' },
  ],
}

const registrationSucceeded = computed(() => route.query.registered === '1')

function safeRedirect(): string {
  const redirect = route.query.redirect
  return typeof redirect === 'string' && redirect.startsWith('/') && !redirect.startsWith('//')
    ? redirect
    : '/'
}

async function submit(): Promise<void> {
  errorMessage.value = ''
  try {
    await formRef.value?.validate()
  } catch {
    return
  }

  try {
    await authStore.login({ username: form.username.trim(), password: form.password })
    await router.replace(safeRedirect())
  } catch (error) {
    errorMessage.value = getErrorMessage(error, '登录失败，请稍后重试')
  }
}
</script>

<template>
  <main class="auth-page">
    <el-card class="auth-card" shadow="never">
      <div class="auth-heading">
        <span class="auth-brand">DevMate</span>
        <h1>登录</h1>
        <p>进入你的软件研发工作空间</p>
      </div>

      <el-alert
        v-if="registrationSucceeded"
        title="注册成功，请使用新账号登录"
        type="success"
        :closable="false"
        show-icon
      />
      <el-alert
        v-if="errorMessage"
        :title="errorMessage"
        type="error"
        :closable="false"
        show-icon
      />

      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        @submit.prevent="submit"
      >
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" autocomplete="username" maxlength="50" />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            autocomplete="current-password"
            maxlength="72"
            show-password
          />
        </el-form-item>
        <el-button
          class="auth-submit"
          type="primary"
          native-type="submit"
          :loading="authStore.loading"
        >
          登录
        </el-button>
      </el-form>

      <p class="auth-switch">还没有账号？<RouterLink to="/register">立即注册</RouterLink></p>
    </el-card>
  </main>
</template>
