/// <reference types="vite/client" />

/** 项目自定义环境变量（Vite 的 ImportMetaEnv 已在 vite/client 里声明 env）。 */
interface ImportMetaEnv {
  /** API 基地址；缺省空串（走 Vite dev proxy 到 http://localhost:8080） */
  readonly VITE_API_BASE?: string
}
