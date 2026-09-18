<script setup lang="ts">
import type { FormInstance, FormRules } from 'element-plus'
import { reactive, ref, watch } from 'vue'

import type { ProjectMutationRequest } from '@/api/types'

const props = withDefaults(
  defineProps<{
    initialValue?: ProjectMutationRequest
    submitting?: boolean
    submitLabel?: string
  }>(),
  {
    initialValue: () => ({ name: '', description: null }),
    submitting: false,
    submitLabel: '保存',
  },
)

const emit = defineEmits<{
  submit: [request: ProjectMutationRequest]
  cancel: []
}>()

const formRef = ref<FormInstance>()
const form = reactive({ name: '', description: '' })
const validationMessage = ref('')

watch(
  () => props.initialValue,
  (value) => {
    form.name = value.name
    form.description = value.description ?? ''
  },
  { immediate: true },
)

const rules: FormRules<typeof form> = {
  name: [
    {
      validator: (_rule, value: string, callback) => {
        const normalized = value.trim()
        if (!normalized) {
          callback(new Error('请输入项目名称'))
        } else if (normalized.length > 100) {
          callback(new Error('项目名称不能超过 100 个字符'))
        } else {
          callback()
        }
      },
      trigger: 'blur',
    },
  ],
  description: [
    {
      validator: (_rule, value: string, callback) => {
        if (value.trim().length > 1000) {
          callback(new Error('项目描述不能超过 1000 个字符'))
        } else {
          callback()
        }
      },
      trigger: 'blur',
    },
  ],
}

async function submit(): Promise<void> {
  if (props.submitting) {
    return
  }
  const name = form.name.trim()
  const description = form.description.trim()
  if (!name) {
    validationMessage.value = '请输入项目名称'
    return
  }
  if (name.length > 100) {
    validationMessage.value = '项目名称不能超过 100 个字符'
    return
  }
  if (description.length > 1000) {
    validationMessage.value = '项目描述不能超过 1000 个字符'
    return
  }
  validationMessage.value = ''
  try {
    await formRef.value?.validate()
  } catch {
    return
  }
  emit('submit', { name, description: description || null })
}
</script>

<template>
  <el-form
    ref="formRef"
    class="project-form"
    :model="form"
    :rules="rules"
    label-position="top"
    @submit.prevent="submit"
  >
    <el-alert
      v-if="validationMessage"
      class="form-validation-alert"
      :title="validationMessage"
      type="error"
      :closable="false"
      show-icon
    />
    <el-form-item label="项目名称" prop="name">
      <el-input v-model="form.name" name="project-name" autocomplete="off" />
      <p class="character-count" aria-live="polite">{{ form.name.trim().length }}/100</p>
    </el-form-item>
    <el-form-item label="项目描述（可选）" prop="description">
      <el-input v-model="form.description" name="project-description" type="textarea" :rows="6" />
      <p class="character-count" aria-live="polite">{{ form.description.trim().length }}/1000</p>
    </el-form-item>
    <div class="form-actions">
      <el-button :disabled="submitting" @click="emit('cancel')">取消</el-button>
      <el-button type="primary" native-type="submit" :loading="submitting">
        {{ submitLabel }}
      </el-button>
    </div>
  </el-form>
</template>

<style scoped>
.project-form {
  width: 100%;
}

.form-validation-alert {
  margin-bottom: 18px;
}

.character-count {
  width: 100%;
  margin: 4px 0 0;
  color: #909399;
  font-size: 12px;
  text-align: right;
}

.form-actions {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
}
</style>
