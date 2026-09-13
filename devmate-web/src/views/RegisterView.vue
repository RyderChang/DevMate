<script setup lang="ts">
import type { FormInstance, FormRules } from 'element-plus'
import { reactive, ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'

import { getErrorMessage } from '@/api/errors'
import { useAuthStore } from '@/stores/auth'

interface RegisterForm {
  username: string
  nickname: string
  password: string
  confirmPassword: string
}

const formRef = ref<FormInstance>()
const form = reactive<RegisterForm>({
  username: '',
  nickname: '',
  password: '',
  confirmPassword: '',
})
const errorMessage = ref('')
const router = useRouter()
const authStore = useAuthStore()

const rules: FormRules<RegisterForm> = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { max: 50, message: '用户名不能超过 50 个字符', trigger: 'blur' },
  ],
  nickname: [{ max: 50, message: '昵称不能超过 50 个字符', trigger: 'blur' }],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 8, max: 72, message: '密码长度应为 8 到 72 个字符', trigger: 'blur' },
  ],
  confirmPassword: [{ required: true, message: '请再次输入密码', trigger: 'blur' }],
}

async function submit(): Promise<void> {
  errorMessage.value = ''
  try {
    await formRef.value?.validate()
  } catch {
    return
  }
  if (form.password !== form.confirmPassword) {
    errorMessage.value = '两次输入的密码不一致'
    return
  }

  try {
    await authStore.register({
      username: form.username.trim(),
      password: form.password,
      nickname: form.nickname.trim() || undefined,
    })
    await router.replace({ name: 'login', query: { registered: '1' } })
  } catch (error) {
    errorMessage.value = getErrorMessage(error, '注册失败，请稍后重试')
  }
}
</script>

<template>
  <main class="auth-page">
    <el-card class="auth-card" shadow="never">
      <div class="auth-heading">
        <span class="auth-brand">DevMate</span>
        <h1>创建账号</h1>
        <p>注册后即可登录 DevMate</p>
      </div>

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
        <el-form-item label="昵称（可选）" prop="nickname">
          <el-input v-model="form.nickname" autocomplete="nickname" maxlength="50" />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            autocomplete="new-password"
            maxlength="72"
            show-password
          />
        </el-form-item>
        <el-form-item label="确认密码" prop="confirmPassword">
          <el-input
            v-model="form.confirmPassword"
            type="password"
            autocomplete="new-password"
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
          注册
        </el-button>
      </el-form>

      <p class="auth-switch">已有账号？<RouterLink to="/login">返回登录</RouterLink></p>
    </el-card>
  </main>
</template>
