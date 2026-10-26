import type { DevLineWebviewContext, DevLineWebviewSdk } from '@devline/extension-devkit'
export interface DeckContext extends DevLineWebviewContext { colors: Record<string, string> }
type DeckSdk = DevLineWebviewSdk<DeckContext>

declare global { interface Window { devline: DeckSdk } }
export const sdk = window.devline
export interface DeckSettings { host: string; port: number; user: string; identityFile: string; backend: 'javafx' | 'desktop-web'; sdkPath: string; appId: string; development: boolean; cloudDirectory: string }
export interface DeckStatus { toolchain: { ready: boolean; bytes: number; target: string }; settings: Partial<DeckSettings>; hardwareVerified: boolean; capabilities: { device: boolean; steamworks: boolean }; operation?: DeckOperation }
export interface DeckOperation { id: string; kind: string; state: string; downloaded: number; total: number; data: string; cursor: number; error: string | null; result: Record<string, unknown> | null }
