# must-monitor

Моніторинг інвертора MUST через старий Android-телефон або ТВ-приставку, без фірмового Wi-Fi модуля.

**Для кого:** у вас є інвертор MUST (PV18 / HBP18 тощо), але немає Wi-Fi-донгла, або ви не хочете залежати від хмари виробника. Зате є вільний Android: старий телефон, планшет чи ТВ-приставка. Підключаєте його USB-кабелем до порту інвертора, і він стає локальним сервером моніторингу:

- веб-дашборд у домашній мережі: заряд батареї, навантаження, PV, мережа, графіки за добу;
- Telegram-сповіщення, коли світло зникає і з'являється (зі станом батареї та прогнозом автономності);
- історія за 24 год зберігається на самому пристрої, без хмари й реєстрацій;
- метрики для Prometheus / Grafana, якщо хочеться більшого.

Потрібно: Android 8.0+, USB-кабель інвертора (CH340, у комплекті з MUST) і OTG-перехідник для телефона. На приставці OTG не потрібен. Пристрій має живитися від виходу інвертора, а роутер бажано теж.

---

Android app that turns a spare phone / TV box into a monitor for a MUST PV18 / HBP18 inverter connected over USB (CH340, Modbus RTU, 19200 8N1, slave 4).

## Supported models

Tested: **HBP18**. Expected to work (same PV1800-family Modbus map, registers 25201+ / 15201+): PV18 / PV1800 (VPM, VHM, VPK), PH1800, EP1800. Other MUST lines (PV19, PV3xxx, EP20/EP30, PH3000/5000…) may use a different protocol or register map and are not verified; use `/api/raw?addr=...` to check what your unit answers.

## Endpoints (port 8080)

- `/` – web dashboard
- `/metrics` – Prometheus metrics
- `/api/state` – current values as JSON
- `/api/history` – last 24h, one point per 30s (averaged over polls; `must_load_power_max_watts` is the peak). Stored in `files/history.csv`, survives restarts
- `/api/events` – grid on/off transitions (`[ts_ms, on]`) and current grid state
- `/api/settings` – GET/POST Telegram settings (token is never returned, only its last 4 chars)
- `/api/telegram/test`, `/api/telegram/detect` – POST: send test message / find chat id via `getUpdates`
- `/api/raw?addr=25201&count=10` – raw holding register read, for exploring registers

## Build

Requirements: JDK 17 and the Android SDK (platform 34, build-tools). Point Gradle at the SDK with `local.properties` (not committed):

```properties
sdk.dir=/path/to/android-sdk
```

or set `ANDROID_HOME`. Then:

```sh
./gradlew :app:assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Install it over USB debugging:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

or copy the APK to the device and open it (allow installing from unknown sources). For a TV box, `adb connect BOX_IP:5555` first.

CI: pushing a tag `v*` builds the APK on GitHub and attaches it to a release:

```sh
git tag v0.2.0 && git push origin v0.2.0
```

## Phone setup

1. Install the APK, plug the inverter via OTG, tick "use by default" for Must Monitor.
2. Allow ignoring battery optimizations when asked.
3. Settings → Apps → Must Monitor → Battery → Unrestricted.
4. Reserve a static IP for the phone on the router.

## Telegram notifications

Open the dashboard → "Telegram-сповіщення": paste the bot token from @BotFather, write anything to the bot, press "Знайти chat id", save, press "Тест".

A message is sent when the grid disappears (battery %, load, estimated runtime) and when it comes back (how long it was out).
Grid detection: on above 170 V, off below 150 V, confirmed by 3 consecutive polls (~15 s). If the internet is down, messages are retried every 30 s for up to 24 h and keep the original event time.

## Prometheus

```yaml
- job_name: must
  scrape_interval: 15s
  static_configs:
    - targets: ["PHONE_IP:8080"]
```

## Notes

- `must_battery_soc_estimate_percent` is estimated from battery voltage (8S LiFePO4), not read from the BMS.
- `must_grid_present` is the debounced grid state used for notifications.
- PV/charger registers (15201+) are read separately; if the unit does not answer them, `chargerError` in `/api/state` shows why.
