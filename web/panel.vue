<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { UIButton, UIIcon, UIInfoTip, UIInput, UISelect, UIModal, UIModalProvider, vUITooltip as vTippy } from '@devline/ui-web/controls'
import DeviceSession from './DeviceSession.vue'
import { sdk } from './sdk'
import type { DeckOperation, DeckSettings, DeckStatus } from './sdk'
import en from './locales/en.json'
import ru from './locales/ru.json'
const context = ref(sdk.getContext())
function appearance() {
  document.documentElement.dataset.theme = context.value.theme
  for (const [key, value] of Object.entries(context.value.colors ?? {})) if (key.startsWith('--')) document.documentElement.style.setProperty(key, value)
}
appearance()
const unsubscribe = sdk.onContextChanged(value => {
  const previous = context.value
  context.value = value; appearance()
  if (value.visible && (!previous.visible || previous.projectFile !== value.projectFile)) {
    if (operation.value?.state === 'running') schedule()
    else void refresh().catch(cause => { if (!disposed && context.value.visible) { error.value = String(cause); errorMethod.value = 'status' } })
  }
})
const t = (key: keyof typeof en) => (context.value.locale.startsWith('ru') ? ru : en)[key]
const tab = ref('build'), status = ref<DeckStatus>(), operation = ref<DeckOperation>(), output = ref(''), error = ref(''), detailsOpen = ref(false)
const errorMethod = ref('')
const pluginPath = ref(''), exportingPlugin = ref(false)
const configured = computed(() => !!status.value?.settings.host?.trim())
const selectedBackend = ref<DeckSettings['backend']>(), dispatching = ref(false)
const backend = computed(() => selectedBackend.value ?? status.value?.settings.backend ?? 'javafx')
function selectBackend(value: unknown) { if (value === 'javafx' || value === 'desktop-web') selectedBackend.value = value }
const connectionKey = computed(() => JSON.stringify([status.value?.settings.host, status.value?.settings.port, status.value?.settings.user, status.value?.settings.identityFile]))
const deviceActions = [
  { id: 'check', icon: 'plug' }, { id: 'deploy', icon: 'upload' },
] as const
const running = computed(() => operation.value?.state === 'running')
const detailsTab = ref<'log' | 'diagnostics'>('log')
function showDetails(mode: 'log' | 'diagnostics' = 'log') { detailsTab.value = mode; detailsOpen.value = true }
const progress = computed(() => operation.value?.total ? Math.min(100, Math.floor(operation.value.downloaded / operation.value.total * 100)) : undefined)
const phase = computed(() => {
  if (operation.value?.state === 'cancelled') return t('phaseCancelled')
  if (operation.value?.state === 'failed') return t('phaseFailed')
  if (operation.value?.state === 'succeeded') return t('phaseReady')
  const line = output.value.trim().split('\n').at(-1) ?? ''
  return t(/extract|unpack/i.test(line) ? 'phaseExtracting' : /sha-256|verif|checksum|layout|receipt/i.test(line) ? 'phaseVerifying' : /download/i.test(line) ? 'phaseDownloading' : 'phasePreparing')
})
const errorSummary = computed(() => {
  const method = errorMethod.value || operation.value?.kind
  if (['check', 'run', 'deploy'].includes(method ?? '') && /connect|timeout|timed out|refused|unreachable|resolve|host key|permission denied|authentication|publickey|ssh.*(?:fail|error)|broken pipe|no route/i.test(error.value)) return t('sshConnectionError')
  if (/existing Linux toolchain.*incomplete/i.test(error.value)) return t('existingToolchainError')
  if (/layout|missing.*(?:runtime|libcef)|not found.*(?:java|chromium)/i.test(error.value)) return t('layoutError')
  if (/SHA-256|checksum/i.test(error.value)) return t('checksumError')
  if (method === 'prepare' && /download|HTTP|timeout|connect|SSL/i.test(error.value)) return t('downloadError')
  return error.value.replace(/^Error:\s*/, '')
})
const downloadSize = computed(() => `${new Intl.NumberFormat(context.value.locale, { maximumFractionDigits: 0 }).format((status.value?.toolchain.bytes ?? 0) / 1_000_000)} ${t('megabytes')}`)
const details = computed(() => JSON.stringify({ ...status.value, ...(error.value ? { error: error.value } : {}) }, null, 2))
let disposed = false, timer: ReturnType<typeof setTimeout> | undefined
let refreshing: Promise<void> | undefined
function refresh() {
  if (!refreshing) refreshing = readStatus().finally(() => { refreshing = undefined })
  return refreshing
}
async function readStatus() {
  const value = await sdk.call('status', {}) as DeckStatus
  if (disposed) return
  status.value = value
  if (errorMethod.value === 'status') { error.value = ''; errorMethod.value = '' }
  if (value.operation && operation.value?.id !== value.operation.id) {
    operation.value = value.operation; output.value = value.operation.data; error.value = value.operation.error ?? ''; errorMethod.value = value.operation.kind
    if (value.operation.state === 'running' || value.operation.data.length >= 32 * 1024) schedule()
  }
}
async function exportPlugin() {
  if (exportingPlugin.value) return
  exportingPlugin.value = true; error.value = ''
  try { const result = await sdk.call('plugin', {}) as { path: string }; if (!disposed) pluginPath.value = result.path }
  catch (cause) { if (!disposed) { error.value = String(cause); errorMethod.value = 'plugin' } }
  finally { if (!disposed) exportingPlugin.value = false }
}
function schedule() { if (!disposed && !timer) timer = setTimeout(() => { timer = undefined; void poll() }, 600) }
async function poll() {
  if (!operation.value || disposed || !context.value.visible) return
  try {
    const value = await sdk.call('read', { id: operation.value.id, cursor: operation.value.cursor }) as DeckOperation
    if (disposed) return
    output.value = (output.value + value.data).slice(-65536); operation.value = value
    if (value.error) { error.value = value.error; errorMethod.value = value.kind }
    if (value.state === 'running' || value.data.length >= 32 * 1024) schedule(); else await refresh()
  } catch (cause) { if (!disposed) error.value = String(cause) }
}
async function action(method: string) {
  if (running.value || dispatching.value) return
  const requestedBackend = selectedBackend.value
  dispatching.value = true; error.value = ''; errorMethod.value = method
  try {
    await refresh()
    if (disposed || !context.value.visible || running.value) return
    const value = await sdk.call(method, ['run', 'deploy'].includes(method) ? { backend: requestedBackend ?? status.value?.settings.backend ?? 'javafx' } : {}) as DeckOperation
    if (disposed) return
    operation.value = value; output.value = value.data
    if (value.error) error.value = value.error
    if (value.state === 'running') schedule()
  } catch (cause) { if (!disposed) error.value = String(cause) }
  finally { if (!disposed) dispatching.value = false }
}
async function cancel() {
  try {
    if (operation.value) {
      const value = await sdk.call('cancel', { id: operation.value.id }) as DeckOperation
      // Cancel returns a snapshot from cursor zero, not an incremental chunk.
      operation.value = value; output.value = value.data
    }
  } catch (cause) { error.value = String(cause); errorMethod.value = 'cancel' }
}
void refresh().catch(cause => { error.value = String(cause); errorMethod.value = 'status' })
onBeforeUnmount(() => { disposed = true; unsubscribe(); clearTimeout(timer) })
</script>

<template>
  <UIModalProvider>
  <main class="deck-panel">
    <nav class="deck-panel__tabs" aria-label="Steam Deck">
      <UIButton v-for="id in ['build', 'device', 'steamworks'] as const" :key="id" variant="quiet" :aria-pressed="tab === id" @click="tab = id">{{ t(id) }}</UIButton>
    </nav>
    <section v-if="tab === 'build'" class="deck-panel__build">
      <div class="deck-panel__intro" role="status">
        <UIIcon :name="status?.toolchain.ready ? 'check-circle' : 'download'" :size="15" :class="{ 'deck-panel__ready': status?.toolchain.ready }" />
        <strong>{{ !status ? t('checking') : status.toolchain.ready ? t('ready') : t('setupTitle') }}</strong>
        <UIInfoTip :label="t('build')" :text="status?.toolchain.ready ? t('profileHelp') + '\n' + t('offline') : t('setupDescription') + '\n' + t('offline')" />
      </div>
      <dl class="deck-panel__summary">
        <div><dt>{{ t('buildTypes') }}</dt><dd>Native · Chromium</dd></div>
        <div v-if="status && !status.toolchain.ready"><dt>{{ t('downloadSize') }}<UIInfoTip :label="t('downloadSize')" :text="t('downloadHelp')" /></dt><dd>{{ downloadSize }}</dd></div>
      </dl>
      <UIButton v-if="!status?.toolchain.ready" class="deck-panel__prepare" variant="primary" :disabled="running || dispatching || !status" :busy="running && operation?.kind === 'prepare'" @click="action('prepare')"><UIIcon v-if="!running" name="download" :size="14" />{{ operation?.state === 'failed' || operation?.state === 'cancelled' ? t('retry') : t('prepare') }}</UIButton>
      <div v-if="!status?.hardwareVerified" class="deck-panel__status"><span>{{ t('pending') }}</span><UIInfoTip :label="t('pending')" :text="t('hardwareHelp')" /></div>
    </section>
    <section v-else-if="tab === 'device'" class="deck-panel__device">
      <dl class="deck-panel__summary deck-panel__connection"><div><dt>{{ t('host') }}</dt><dd>{{ status?.settings.host || t('notConfigured') }}</dd></div></dl>
      <div v-if="!configured" class="deck-panel__status"><span>{{ t('configureFirst') }}</span></div>
      <div class="deck-panel__device-bar">
        <div class="deck-panel__actions" role="group" :aria-label="t('deviceActions')"><UIButton v-for="item in deviceActions" :key="item.id" variant="quiet" class="deck-panel__icon" :aria-label="t(item.id)" v-tippy="t(item.id)" :disabled="running || dispatching || !configured || !status?.capabilities.device" @click="action(item.id)"><UIIcon :name="item.icon" :size="15" /></UIButton></div>
        <UIInfoTip :label="t('deviceActions')" :text="t('deviceHelp')" />
      </div>
      <div class="deck-panel__run-bar"><UISelect :model-value="backend" :options="[{ value: 'javafx', label: 'Native' }, { value: 'desktop-web', label: 'Chromium' }]" :aria-label="t('backend')" :disabled="running || dispatching || !status" @update:model-value="selectBackend" /><UIButton class="deck-panel__run" variant="primary" :disabled="running || dispatching || !configured || !status?.toolchain.ready || !status?.capabilities.device" :busy="running && operation?.kind === 'run'" @click="action('run')"><UIIcon name="play-fill" :size="14" />{{ t('runOnDeck') }}</UIButton></div>
      <div v-if="status && !status.capabilities.device" class="deck-panel__status"><span>{{ t('deviceUnavailable') }}</span><UIInfoTip :label="t('deviceUnavailable')" :text="t('implementationPending')" /></div>
      <DeviceSession :operation="operation" :configured="configured" :connection-key="connectionKey" :locale="context.locale" />
    </section>
    <section v-else class="deck-panel__steamworks">
      <p>{{ t('steamworksTaskHelp') }}</p>
      <UIButton class="deck-panel__prepare" :disabled="running || dispatching || !status?.settings.sdkPath?.trim() || !status?.settings.appId?.trim() || !status?.capabilities.steamworks" @click="action('prepareSteamworks')"><UIIcon name="box-seam" :size="14" />{{ t('prepareSteamworks') }}</UIButton>
      <div v-if="status && !status.capabilities.steamworks" class="deck-panel__status"><span>{{ t('steamworksUnavailable') }}</span><UIInfoTip :label="t('steamworksUnavailable')" :text="t('implementationPending')" /></div>
    </section>
    <div v-if="running" class="deck-panel__progress"><div class="deck-panel__progress-description" role="status"><span>{{ phase }}<template v-if="progress !== undefined"> · {{ progress }}%</template></span><UIInfoTip :label="t('operationLog')" :text="t('operationHelp')" /></div><div class="deck-panel__actions"><UIButton variant="quiet" class="deck-panel__icon" :aria-label="t('operationLog')" v-tippy="t('operationLog')" @click="showDetails()"><UIIcon name="terminal" :size="14" /></UIButton><UIButton @click="cancel">{{ t('cancel') }}</UIButton></div></div>
    <div v-if="error" class="deck-panel__error" role="alert"><span>{{ errorSummary }}</span><UIButton variant="quiet" @click="showDetails()">{{ t('operationLog') }}</UIButton></div>
    <div class="deck-panel__footer"><div class="deck-panel__actions deck-panel__footer-tools"><UIButton class="deck-panel__icon deck-panel__plugin" variant="quiet" :disabled="exportingPlugin" :busy="exportingPlugin" v-tippy="t('deckyPlugin')" :aria-label="t('deckyPlugin')" @click="exportPlugin"><UIIcon name="controller" :size="15" /></UIButton><UIButton class="deck-panel__icon deck-panel__diagnostics" variant="quiet" v-tippy="t('details')" :aria-label="t('details')" :aria-expanded="detailsOpen" aria-controls="deck-technical-details" @click="showDetails(output || error ? 'log' : 'diagnostics')"><UIIcon name="code-square" :size="15" /></UIButton></div></div>
    <div v-if="pluginPath" class="deck-panel__plugin-path"><UIInput :model-value="pluginPath" readonly :aria-label="t('deckyPluginPath')" /><UIInfoTip :label="t('deckyPlugin')" :text="t('deckyPluginHelp')" /></div>
    <UIModal id="deck-technical-details" v-model:open="detailsOpen" :title="t('details')" size="md" body-scroll>
      <div class="deck-panel__diagnostics-modal">
        <nav class="deck-panel__tabs" :aria-label="t('details')"><UIButton v-for="id in ['log', 'diagnostics'] as const" :key="id" variant="quiet" :aria-pressed="detailsTab === id" @click="detailsTab = id">{{ t(id === 'log' ? 'operationLog' : 'diagnostics') }}</UIButton></nav>
        <template v-if="detailsTab === 'log'"><p v-if="error && !output.includes(error)" class="deck-panel__error is-selectable">{{ error }}</p><pre class="deck-panel__output is-selectable" tabindex="0" :aria-label="t('operationLog')">{{ output || t('logEmpty') }}</pre></template>
        <pre v-else class="deck-panel__output is-selectable" tabindex="0" :aria-label="t('diagnostics')">{{ details }}</pre>
      </div>
    </UIModal>
  </main>
  </UIModalProvider>
</template>

<style lang="scss" scoped src="./panel.scss" />
<style lang="scss">
@use '@devline/ui-web/scrollbar.scss' as scrollbar;
html { @include scrollbar.compact-scrollbar; }
.ui-modal-layer { padding: var(--space-2); align-items: center; }
.ui-modal { max-height: calc(100dvh - 12px); border-radius: var(--ui-modal-radius); }
.ui-modal__body { @include scrollbar.compact-scrollbar; padding: var(--space-3); }
.ui-modal__close.ui-button { width: var(--ui-icon-button-size); height: var(--ui-icon-button-size); }
</style>
