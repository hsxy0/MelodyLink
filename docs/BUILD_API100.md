# MelodyLink API 100 构建说明

## 构建契约

- Xposed 最低和目标 API 都为 100；API 102 热重载配置已移除。
- Maven Central 未发布 `io.github.libxposed:api:100.0.0`。使用固定来源的 `xposed-api` 模块，仅在 `compileOnly` 与 JVM 测试中依赖，不能打入 APK。
- 来源、固定提交及许可证见 `../xposed-api/SOURCE.md` 和 `LICENSE-2.0.txt`，与本机 librepods 的 API 100 来源一致。
- 模块入口为 `HookModule(XposedInterface, ModuleLoadedParam)`，生命周期为 `onPackageLoaded`。`Api100Interception` 将现有方法拦截逻辑接入静态 `Hooker.before`。
- `proceed()` 使用 API 100 的 `invokeOrigin`，绕过同一方法的其他 Hook；与 API 102 的链式调用不完全等价。多模块共同 Hook 的情况需要实机验证。
- Hook 异常发生在原方法调用前时，交还框架执行原方法；发生在调用后时保留原结果或原异常，避免重复副作用。
- 根目录历史 `io/` 文件不是 Gradle 源集，不参与构建；实际契约以 `xposed-api/src/main/java/` 为准。

## 本地与 CI

需要 JDK 21、Android SDK `platforms;android-37.0`。设置 `ANDROID_HOME`，或在忽略提交的 `local.properties` 配置 `sdk.dir`。

```sh
bash gradlew --no-daemon :app:assembleDebug :app:testDebugUnitTest
python scripts/verify_api100.py app/build/outputs/apk/debug/app-debug.apk
```

Windows 使用 `gradlew.bat`。CI 为 `.github/workflows/android.yml`，推送构建相关变更会执行构建、单测和 APK 内容检查；在 Actions 的 Artifacts 下载 `MelodyLink-api100-debug-<run-id>`。

## 仓库 SSH

- 注册仓库：`hsxy0/MelodyLink`，Deploy Key 具有此仓库读写权限。
- 标题：`MelodyLink-codex-deploy-20260929`。
- 本地私钥：项目根目录 `deploy_key`；公钥：`deploy_key.pub`。两者均被 Git 忽略。
- 公钥指纹：`SHA256:6gLaIlADJEMRLcCTc9LVcQNE3KpHDKMQKJOJkqm1Zs8`。
- 仓库级 `core.sshCommand`：`ssh -i ./deploy_key -o IdentitiesOnly=yes`，保留主机校验。
- push URL：`git@github.com:hsxy0/MelodyLink.git`。在仓库根目录运行 Git 命令；其他 clone 需单独配置密钥。

## 实机验收

1. 下载本次通过 CI 的 Debug APK 并安装。若设备装有其他签名的同包名应用，先确认签名兼容和数据备份。
2. 在支持现代 API 100 的框架管理器启用 MelodyLink，作用域选择 `com.oplus.melody`。
3. 强制停止并重启欢律；API 100 不使用热重载。
4. 检查框架日志中的 `MelodyLinkObserver`，确认出现 `hooked`，且无入口构造函数、类加载或方法缺失错误。
5. 分别验证实际持有耳机的连接状态、电量、降噪切换、详情图和高级设置。若同时启用其他欢律 Hook 模块，应单独验证共存行为。

APK 打包通过不代表以上实机操作已验收。
