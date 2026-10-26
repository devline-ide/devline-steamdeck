export interface DeckContext { locale: string; theme: string; visible: boolean; projectFile?: string; colors: Record<string, string>; effects?: Record<string, string>; view?: { id: string; location: 'dock' | 'sidebar' | 'settings'; settingsScope?: 'user' | 'workspace'; readOnly?: boolean } }
interface DeckSdk {
  ready: Promise<void>
  getContext(): DeckContext
  onContextChanged(listener: (context: DeckContext) => void): () => void
  call(method: string, params?: Record<string, unknown>): Promise<unknown>
}
declare global { interface Window { devline: DeckSdk } }
export const sdk = window.devline
export interface DeckSettings { host: string; port: number; user: string; identityFile: string; backend: 'javafx' | 'desktop-web'; sdkPath: string; appId: string; development: boolean; cloudDirectory: string }
export interface DeckStatus { toolchain: { ready: boolean; bytes: number; target: string }; settings: Partial<DeckSettings>; hardwareVerified: boolean; capabilities: { device: boolean; steamworks: boolean }; operation?: DeckOperation }
export interface DeckOperation { id: string; kind: string; state: string; downloaded: number; total: number; data: string; cursor: number; error: string | null; result: Record<string, unknown> | null }
