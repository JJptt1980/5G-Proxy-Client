# 5G Proxy Client GW（非官方修改版）

> 這是 [tokyoxpa3/5G-Proxy-Client](https://github.com/tokyoxpa3/5G-Proxy-Client) 的 **非官方 fork**，
> 不是原作者發布的版本，有問題請不要回報給原專案。

## 授權與版權

- 原專案採用 **MIT 授權**，版權屬於原作者 tokyoxpa3。
- 本 fork 保留原本的 [LICENSE](LICENSE) 檔案，內容未做任何修改。
- 本 fork 的修改部分同樣以 MIT 授權釋出。

## 適用情境

平板（或其他裝置）連到手機的 Wi-Fi 熱點，手機上跑 5G Proxy Pro 當代理伺服器。

問題是：手機熱點的 IP 每次開可能都不一樣，每次都要重新查 IP、重新填很麻煩。
不過連上熱點後，**手機一定是這個 Wi-Fi 的預設閘道**，所以只要把伺服器位址設成「用閘道」，
就不用管 IP 變成多少。

## 改了哪些東西

| 檔案 | 修改內容 |
| --- | --- |
| `app/src/main/java/com/tokyoxpa3/socksclient/GatewayResolver.kt`（新增） | 伺服器位址填 `gateway` 或 `auto`（不分大小寫）時，改用目前 Wi-Fi 的預設閘道 IPv4 |
| `TunSocksService.kt` | 啟動連線時、以及軟重連重新解析伺服器位址時，都會先經過 `GatewayResolver` |
| `TunSocksService.kt`、`MainActivity.kt`、`BootReceiver.kt` | 伺服器位址沒設定過時，預設值從空白改成 `gateway` |
| `app/build.gradle` | debug 版加上 `applicationIdSuffix ".gw"` 和 `versionNameSuffix "-gw"`，可以和原版 App 同時安裝 |
| `app/src/debug/res/values*/strings.xml`（新增） | debug 版的 App 名稱改為「5G Proxy Client GW」，伺服器欄位提示加上 gateway 說明 |
| `gradlew` | 加上執行權限 |

注意：套件名稱和 App 名稱的變更只套用在 **debug 版**；release 版的套件名稱和原版相同。

## 使用方式

1. 平板連上手機熱點。
2. 手機開啟 5G Proxy Pro。
3. 平板打開 5G Proxy Client GW，伺服器位址填 `gateway`（新安裝預設就是），埠號照 5G Proxy Pro 顯示的填。
4. 按連線。如果沒連上 Wi-Fi，會顯示找不到 Wi-Fi 閘道的錯誤。

## 編譯

需要 JDK 17 和 Android SDK。

```bash
export JAVA_HOME="$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home" && ./gradlew assembleDebug
```

產出的 APK 在 `app/build/outputs/apk/debug/app-debug.apk`。
