import { createApp } from 'vue'
import './style.css'
import App from './App.vue'
import { installGlobalLauncherLogHandlers } from './services/launcherLog'

installGlobalLauncherLogHandlers()
createApp(App).mount('#app')
