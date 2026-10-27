<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { UIButton, UIIcon, UIInfoTip, UISelect, vUITooltip as vTippy } from '@devline/ui-web/controls'
import { buildGroups, buildOptions, buildStamp, buildBackend, selectedBuild, type DeckBuild } from '@devline/deck-build-inventory'
import { sdk } from './sdk'
import type { DeckOperation } from './sdk'
import en from './locales/en.json'
import ru from './locales/ru.json'

const props = defineProps<{ operation?: DeckOperation; configured: boolean; locale: string; connectionKey?: string }>()
const t = (key: keyof typeof en) => (props.locale.startsWith('ru') ? ru : en)[key]
interface Session { runId: string; buildId: string; pid?: number; rssBytes?: number; state: string; exitCode?: number }
interface Status { builds: DeckBuild[]; running: Session | null; lastRun: Session | null; disk: { freeBytes: number; quotaBytes: number; usedBytes: number } }
const enabled = ref(false), preview = ref(true), logVisible = ref(false), state = ref<Status>(), image = ref(''), frameReason = ref(''), output = ref(''), error = ref(''), pending = ref(false)
const selected = ref(''), history = ref(false)
const builds = computed(() => state.value?.builds ?? []), groups = computed(() => buildGroups(builds.value))
const chosen = computed(() => selectedBuild(builds.value, selected.value, history.value))
const choices = computed(() => buildOptions(builds.value, history.value).map(build => ({ value: build.id, label: `${build.name} · ${buildBackend(build)} · ${size(build.sizeBytes)}${history.value || groups.value.some(group => group[0]?.id !== build.id && group[0]?.name === build.name) ? ' · ' + buildStamp(build, props.locale) : ''}` })))
let disposed = false, timer: ReturnType<typeof setTimeout> | undefined, cursor = 0, runId = '', polling = false, generation = 0
function clearTimer() { clearTimeout(timer); timer = undefined }
function schedule() { if (!disposed && !timer && !pending.value && enabled.value && props.configured && sdk.getContext().visible) timer = setTimeout(() => { timer = undefined; void poll() }, 1000) }
function size(value: number) { return new Intl.NumberFormat(props.locale, { maximumFractionDigits: 0 }).format(value / 1024 / 1024) + ' MiB' }
function accept(value: Status) {
  state.value = value; error.value = ''
  const session = value.running ?? value.lastRun
  if (session?.runId !== runId) { runId = session?.runId ?? ''; cursor = 0; output.value = ''; image.value = ''; frameReason.value = '' }
}
async function poll() {
  if (disposed || polling || !enabled.value || !props.configured) return
  if (!sdk.getContext().visible) { schedule(); return }
  polling = true
  const owner = generation
  try {
    const value = await sdk.call('deviceStatus', {}) as Status
    if (disposed || owner !== generation || !enabled.value) return
    accept(value)
    if (runId) {
      const logs = await sdk.call('logs', { runId, cursor, maxBytes: 16384 }) as { data: string; cursor: number }
      if (disposed || owner !== generation || !enabled.value) return
      cursor = logs.cursor; output.value = (output.value + logs.data).slice(-32768)
    }
    if (value.running && preview.value) {
      const frame = await sdk.call('frame', { runId }) as { available: boolean; mime?: string; data?: string; reason?: string }
      if (disposed || owner !== generation || !enabled.value) return
      image.value = frame.available && frame.mime === 'image/png' && frame.data && frame.data.length <= 3 * 1024 * 1024 && /^[A-Za-z0-9+/=\r\n]+$/.test(frame.data) ? `data:image/png;base64,${frame.data}` : ''
      frameReason.value = frame.reason ?? ''
    } else image.value = ''
  } catch (cause) { if (!disposed && owner === generation && enabled.value) { error.value = String(cause); image.value = '' } }
  finally { polling = false; schedule() }
}
async function stop() {
  if (!runId || pending.value) return
  const owner = generation
  pending.value = true; error.value = ''
  try { await sdk.call('stop', { runId }); if (!disposed && owner === generation) await poll() } catch (cause) { if (!disposed && owner === generation) error.value = String(cause) } finally { if (!disposed) pending.value = false }
}
async function removeBuild() {
  const build = chosen.value
  if (!build || pending.value || props.operation?.state === 'running' || state.value?.running?.buildId === build.id) return
  const owner = ++generation
  const nextId = groups.value.find(group => group.some(item => item.id === build.id))?.find(item => item.id !== build.id)?.id ?? ''
  pending.value = true; clearTimer(); error.value = ''
  try {
    await sdk.call('remove', { artifactId: build.id })
    if (disposed || owner !== generation || !enabled.value) return
    const next = await sdk.call('deviceStatus', {}) as Status
    if (!disposed && owner === generation && enabled.value) { accept(next); selected.value = nextId }
  } catch (cause) { if (!disposed && owner === generation) error.value = String(cause) }
  finally { if (!disposed && owner === generation) { pending.value = false; schedule() } }
}
watch(() => props.connectionKey, () => {
  generation++; clearTimer(); enabled.value = false; pending.value = false; state.value = undefined; selected.value = ''; history.value = false; image.value = ''; frameReason.value = ''; error.value = ''; output.value = ''; runId = ''; cursor = 0
})
watch(() => props.configured, value => { if (!value) { enabled.value = false; state.value = undefined; image.value = '' } })
watch(() => props.operation, operation => {
  if (operation?.state === 'succeeded' && ['run', 'deploy'].includes(operation.kind)) { enabled.value = true; clearTimer(); void poll() }
}, { immediate: true })
watch(enabled, value => { clearTimer(); if (value) void poll(); else image.value = '' })
const unsubscribe = sdk.onContextChanged(context => { clearTimer(); if (context.visible && enabled.value && !pending.value) void poll() })
onBeforeUnmount(() => { disposed = true; clearTimer(); unsubscribe() })
</script>

<template>
  <section class="deck-session">
    <div class="deck-session__toolbar">
      <UIButton variant="quiet" :disabled="!configured" :aria-pressed="enabled" @click="enabled = !enabled"><UIIcon name="display" :size="14" />{{ t('monitor') }}</UIButton>
      <UIInfoTip :label="t('monitor')" :text="t('monitorHelp')" />
      <template v-if="enabled">
        <UIButton class="deck-session__icon" variant="quiet" :aria-label="t('screen')" :aria-pressed="preview" v-tippy="t('screen')" @click="preview = !preview"><UIIcon name="camera-video" :size="14" /></UIButton>
        <UIButton class="deck-session__icon" variant="quiet" :aria-label="t('logs')" :aria-expanded="logVisible" v-tippy="t('logs')" @click="logVisible = !logVisible"><UIIcon name="terminal" :size="14" /></UIButton>
        <UIButton v-if="state?.running" class="deck-session__icon" variant="quiet" :aria-label="t('stop')" :disabled="pending" v-tippy="t('stop')" @click="stop"><UIIcon name="stop-fill" :size="14" /></UIButton>
      </template>
    </div>
    <template v-if="enabled">
      <div v-if="error" class="deck-session__error" role="alert"><span>{{ t('connectionError') }}</span><UIInfoTip :label="t('connectionError')" :text="error" /><UIButton class="deck-session__icon" variant="quiet" :aria-label="t('retry')" v-tippy="t('retry')" @click="poll"><UIIcon name="arrow-clockwise" :size="14" /></UIButton></div>
      <template v-else-if="state">
        <div class="deck-session__builds">
          <div class="deck-session__status"><strong>{{ t('deviceBuilds') }}</strong><small>{{ size(state.disk.usedBytes) }} / {{ size(state.disk.quotaBytes) }}</small></div>
          <UISelect v-if="choices.length" :model-value="chosen?.id ?? ''" :options="choices" :aria-label="t('selectDeviceBuild')" :disabled="pending" @update:model-value="selected = String($event)" />
          <p v-else class="deck-session__hint">{{ t('noDeviceBuilds') }}</p>
          <div v-if="chosen" class="deck-session__build-details"><span>{{ buildStamp(chosen, locale) }}</span><UIButton variant="quiet" :busy="pending" :disabled="pending || operation?.state === 'running' || state.running?.buildId === chosen.id" @click="removeBuild"><UIIcon name="trash" :size="14" />{{ t('deleteDeviceBuild') }} · {{ size(chosen.sizeBytes) }}</UIButton></div>
          <UIButton v-if="builds.length > groups.length" variant="quiet" :aria-pressed="history" :disabled="pending" @click="history = !history">{{ t(history ? 'latestDeviceBuilds' : 'deviceBuildHistory') }}<template v-if="!history"> ({{ builds.length - groups.length }})</template></UIButton>
        </div>
        <div class="deck-session__status" role="status"><span>{{ state.running ? t('remoteRunning') : state.lastRun ? t('remoteStopped') : t('remoteIdle') }}</span><small v-if="state.running?.rssBytes">RAM {{ size(state.running.rssBytes) }}</small></div>
        <img v-if="image && preview && state.running" class="deck-session__frame" :src="image" :alt="t('screen')" />
        <p v-else-if="preview && state.running" class="deck-session__hint">{{ t('waitingFrame') }}<UIInfoTip :label="t('screen')" :text="frameReason || t('monitorHelp')" /></p>
        <pre v-if="logVisible" class="deck-panel__output deck-session__log" tabindex="0" :aria-label="t('logs')">{{ output || t('logEmpty') }}</pre>
      </template>
      <p v-else class="deck-session__hint" role="status">{{ t('checking') }}</p>
    </template>
  </section>
</template>

<style lang="scss" scoped src="./device-session.scss" />
