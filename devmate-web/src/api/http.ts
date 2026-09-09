import axios from 'axios'

const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api',
  timeout: 10_000,
})

http.interceptors.request.use(
  (config) => config,
  (error: unknown) => Promise.reject(error),
)

http.interceptors.response.use(
  (response) => response,
  (error: unknown) => Promise.reject(error),
)

export default http
