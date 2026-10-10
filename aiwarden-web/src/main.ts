/** Element Plus 全量引入（单页应用，不做按需拆包）。 */
import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'

import App from './App.vue'
import router from './router'
import './styles/global.css'

createApp(App).use(router).use(ElementPlus, { locale: zhCn }).mount('#app')
