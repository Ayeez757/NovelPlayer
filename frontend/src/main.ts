import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import './styles/main.css'
import App from './App.vue'

// 前端入口：挂载 Element Plus 和根组件。
createApp(App).use(ElementPlus).mount('#app')
