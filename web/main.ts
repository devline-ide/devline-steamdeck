import { createApp } from 'vue'
import { installUI } from '@devline/ui-web/controls'
import '@devline/ui-web/controls.css'
import App from './panel.vue'
import SettingsPanel from './SettingsPanel.vue'
import { sdk } from './sdk'
void sdk.ready.then(() => {
  const app = createApp(sdk.getContext().view?.location === 'settings' ? SettingsPanel : App)
  installUI(app)
  app.mount('#app')
})
