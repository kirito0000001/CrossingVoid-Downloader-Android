import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

describe('AxTools task environment', () => {
  it('lets task-scoped JDK and Android SDK override legacy defaults', () => {
    const script = readFileSync(
      resolve(process.cwd(), 'Scripts/Publish-AndroidLauncher.ps1'),
      'utf8',
    )

    expect(script).toContain('$env:JAVA_HOME')
    expect(script).toContain('$env:ANDROID_SDK_ROOT')
    expect(script).toContain('C:\\Program Files\\Java\\jdk-23')
    expect(script).toContain('$env:LOCALAPPDATA')
  })
})
