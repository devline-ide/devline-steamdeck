<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref } from 'vue'
import { UIButton, UISwitch, UIIcon, UIInfoTip, UIInput, UISelect, vUITooltip as vTippy } from '@devline/ui-web'
import { sdk } from './sdk'
import type { DeckSettings, DeckStatus } from './sdk'
import en from './locales/en.json'
import ru from './locales/ru.json'

const context = ref(sdk.getContext())
const projectScope = computed(() => context.value.view?.settingsScope === 'workspace')
const keys = computed(() => projectScope.value ? ['appId', 'cloudDirectory', 'development', 'backend'] as const : ['host', 'port', 'user', 'identityFile', 'sdkPath'] as const)
const t = (key: keyof typeof en) => (context.value.locale.startsWith('ru') ? ru : en)[key]
const defaults: DeckSettings = { host: '', port: 22, user: 'deck', identityFile: '', backend: 'javafx', sdkPath: '', appId: '', development: true, cloudDirectory: 'saves' }
const draft = reactive({ ...defaults, port: String(defaults.port) })
const baseline = ref(''), ready = ref(false), loading = ref(false), saving = ref(false), running = ref(false), error = ref(''), saved = ref(false)
const dirty = computed(() => ready.value && JSON.stringify(draft) !== baseline.value)
const disabled = computed(() => !ready.value || loading.value || saving.value || running.value || !!context.value.view?.readOnly)
type TextField = 'host' | 'port' | 'user' | 'identityFile' | 'sdkPath' | 'appId' | 'cloudDirectory'
interface Field { key: TextField; label: keyof typeof en; help?: keyof typeof en; type?: 'number' }
const connectionFields: Field[] = [{ key: 'host', label: 'host', help: 'pairing' }, { key: 'port', label: 'port', type: 'number' }, { key: 'user', label: 'user' }, { key: 'identityFile', label: 'identity', help: 'identityHelp' }]
const steamworksFields = computed<Field[]>(() => projectScope.value ? [{ key: 'appId', label: 'appId', help: 'appIdHelp' }, { key: 'cloudDirectory', label: 'cloud', help: 'cloudHelp' }] : [{ key: 'sdkPath', label: 'sdk', help: 'sdkHelp' }])
const errors = computed(() => {
  const result: Partial<Record<TextField, string>> = {}
  if (draft.host && !/^[A-Za-z0-9][A-Za-z0-9.:-]{0,252}$/.test(draft.host)) result.host = t('invalidHost')
  if (!/^\d+$/.test(draft.port) || Number(draft.port) < 1 || Number(draft.port) > 65535) result.port = t('invalidPort')
  if (draft.user && !/^[a-z_][a-z0-9_-]{0,31}$/.test(draft.user)) result.user = t('invalidUser')
  if (draft.appId && (!/^[1-9][0-9]{0,9}$/.test(draft.appId) || Number(draft.appId) > 4294967295)) result.appId = t('invalidAppId')
  return result
})
let disposed = false
function appearance() {
  document.documentElement.dataset.theme = context.value.theme
  for (const [key, value] of Object.entries(context.value.colors ?? {})) if (key.startsWith('--')) document.documentElement.style.setProperty(key, value)
}
function accept(value: Partial<DeckSettings>) {
  const settings = { ...defaults, ...value }
  Object.assign(draft, settings, { port: String(settings.port) })
  baseline.value = JSON.stringify(draft); ready.value = true
}
async function load() {
  if (!context.value.visible || loading.value || saving.value || dirty.value) return
  loading.value = true; error.value = ''; saved.value = false
  try {
    const status = await sdk.call('status', {}) as DeckStatus
    if (!disposed) { accept(status.settings); running.value = status.operation?.state === 'running' }
  } catch (cause) { if (!disposed) error.value = String(cause) }
  finally { if (!disposed) loading.value = false }
}
async function save() {
  if (disabled.value || !dirty.value || Object.keys(errors.value).length) return
  saving.value = true; error.value = ''; saved.value = false
  try {
    const value = await sdk.call('configure', { settings: Object.fromEntries(keys.value.map(key => [key, key === 'port' ? Number(draft.port) : draft[key]])) }) as Partial<DeckSettings>
    if (!disposed) { accept(value); saved.value = true }
  } catch (cause) { if (!disposed) error.value = String(cause) }
  finally { if (!disposed) saving.value = false }
}
function discard() { if (ready.value && !saving.value) { Object.assign(draft, JSON.parse(baseline.value)); saved.value = false; error.value = '' } }
appearance()
const unsubscribe = sdk.onContextChanged(value => { context.value = value; appearance(); if (value.visible && !dirty.value) void load() })
void load()
onBeforeUnmount(() => { disposed = true; unsubscribe() })
</script>

<template>
  <main class="deck-settings" :aria-label="t('settingsTitle')">
    <section :aria-label="t('connectionSettings')">
      <h3>{{ projectScope ? t('build') : t('connectionSettings') }}<UIInfoTip :label="t('connectionSettings')" :text="t('settingsHelp')" /></h3>
      <div class="deck-settings__fields">
      <div v-for="field in projectScope ? [] : connectionFields" :key="field.key" class="deck-settings__row">
        <label :for="`deck-settings-${field.key}`">{{ t(field.label) }}<UIInfoTip v-if="field.help" :label="t(field.label)" :text="t(field.help)" /></label>
        <UIInput :id="`deck-settings-${field.key}`" :aria-label="t(field.label)" v-model="draft[field.key]" :type="field.type ?? 'text'" :disabled="disabled" :error-message="errors[field.key]" autocomplete="off" spellcheck="false" />
      </div>
      <div v-if="projectScope" class="deck-settings__row"><label for="deck-settings-backend">{{ t('backend') }}</label><UISelect input-id="deck-settings-backend" v-model="draft.backend" :disabled="disabled" :aria-label="t('backend')" :options="[{ value: 'javafx', label: 'Native' }, { value: 'desktop-web', label: 'Chromium' }]" /></div>
      </div>
    </section>
    <section :aria-label="t('steamworks')">
      <h3>{{ t('steamworks') }}</h3>
      <div class="deck-settings__fields">
      <div v-for="field in steamworksFields" :key="field.key" class="deck-settings__row">
        <label :for="`deck-settings-${field.key}`">{{ t(field.label) }}<UIInfoTip v-if="field.help" :label="t(field.label)" :text="t(field.help)" /></label>
        <UIInput :id="`deck-settings-${field.key}`" :aria-label="t(field.label)" v-model="draft[field.key]" :disabled="disabled" :error-message="errors[field.key]" autocomplete="off" spellcheck="false" />
      </div>
      <div v-if="projectScope" class="deck-settings__row"><label for="deck-settings-development">{{ t('development') }}<UIInfoTip :label="t('development')" :text="t('developmentHelp')" /></label><UISwitch id="deck-settings-development" v-model="draft.development" :disabled="disabled" :aria-label="t('development')" /></div>
      </div>
    </section>
    <div v-if="error" class="deck-settings__error is-selectable" role="alert">{{ error }}</div>
    <div v-if="running" class="deck-settings__status" role="status">{{ t('settingsBusy') }}</div>
    <footer class="deck-settings__actions">
      <UIButton variant="primary" :disabled="disabled || !dirty || !!Object.keys(errors).length" :busy="saving" @click="save">{{ t('save') }}</UIButton>
      <UIButton :disabled="loading || saving || !dirty" @click="discard">{{ t('discardChanges') }}</UIButton>
      <UIButton class="deck-settings__refresh" variant="quiet" :disabled="loading || saving || dirty" :busy="loading" :aria-label="t('refresh')" v-tippy="t('refresh')" @click="load"><UIIcon v-if="!loading" name="arrow-clockwise" /></UIButton>
      <span v-if="saved && !dirty" class="deck-settings__status" role="status">{{ t('saved') }}</span>
    </footer>
  </main>
</template>

<style scoped lang="scss" src="./settings-panel.scss" />
