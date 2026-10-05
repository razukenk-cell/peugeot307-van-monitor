# Peugeot 307 CarInfo — README для продолжения проекта в новом чате

> **Назначение этого файла:** если текущий чат закончился, загрузите архив исходников в новый чат и попросите ассистента сначала прочитать этот файл целиком. Здесь собран текущий технический контекст, уже подтверждённые данные, гипотезы, ограничения и ближайший план.

## 1. Цель проекта

Автомобиль: **Peugeot 307, 2003 г.**, ранняя мультиплексная архитектура PSA (VAN + CAN / AEE2001).

В машине установлена Android-магнитола вместо штатной. Штатный верхний дисплей Peugeot остаётся подключён и работает: наружная температура, бортовой компьютер, предупреждения вроде *Antipollution fault* и т. п.

Цель — сделать своё Android-приложение **Peugeot 307 CarInfo**, которое автоматически запускается на магнитоле и отображает данные, уже декодированные CAN/VAN-адаптером:

- двери и багажник;
- кнопки подрулевого пульта;
- наружную температуру;
- запас хода;
- расход, среднюю скорость и другие данные БК, если CAN-box их реально отдаёт;
- по возможности предупреждения BSI;
- позднее — другие доступные параметры.

На первом этапе приложение **только читает данные**. Оно не должно отправлять произвольные команды в VAN/CAN/BSI.

---

## 2. Оборудование

### CAN/VAN box

Используется:

- **SimpleSoft RP5-PA-104**
- SW: **V3.06AYG**
- DC: **20250227**
- P/N: **K0090**

Это адаптер для ранних Peugeot/Citroen VAN-era машин.

### Настройка штатного CANBUS в магнитоле

На магнитоле выбрано:

- CAN box: **SimpleSoft**
- Brand: **Peugeot/Citroen**
- protocol / Canbus ID: **psa_01_sp**
- Model: Other compatible models of the PEUGEOT CITROEN
- Configuration: Common

### Магнитола

- Device: **rk3566_s**
- SoC: RK3566
- экран: **1024x600**, DPI около 200
- Android в меню заявлен как Android 14, но реальная система по Build:
  - **Android 12**
  - SDK 32
  - fingerprint: `rockchip/rk3566_s/rk3566_s:12/SQ3A.220705.003.A1/217:userdebug/release-keys`
- MCU: **1A_V0.1.37_240620**
- System family: **CTC01_P01**
- Audio processor: AK7604
- Radio: SI4755

---

## 3. GitHub

Репозиторий:

`razukenk-cell/peugeot307-van-monitor`

Основная ветка:

`main`

Состояние на момент создания этого handoff-файла: **v0.7**.

GitHub Actions собирает debug APK автоматически.

Основные файлы:

- `app/src/main/java/com/razukenk/peugeot307vanmonitor/MainActivity.java`
- `MonitorService.java`
- `DirectCarDataReader.java`
- `CanScreenAccessibilityService.java`
- `FrameParser.java`
- `VehicleData.java`
- `CarDoorView.java`
- `DiagnosticsExporter.java`
- `CanbusApkExporter.java`
- `SystemProbeExporter.java`

---

## 4. Что было найдено в штатном CANBUS-приложении

С магнитолы было скопировано штатное приложение:

- package: **`com.cartech.service.canbus`**
- system path: **`/system/app/carCanbus/carCanbus.apk`**

Из его анализа были найдены важные внутренние компоненты:

- последовательный интерфейс: **`/dev/ttyCanbus`**
- native library: **`libserial_port.so`**
- sysfs: **`/sys/cartech/canbus`**
- системный Binder/service слой: **`car_server`**
- Java API платформы магнитолы: **`android.cartech.cardata.CarData`**

Вывод: штатное CANBUS-приложение не обязано работать с CAN-box только через графический debug-log. В системе есть внутренний API `CarData`, куда попадают системные данные.

---

## 5. Direct CarData: что подтверждено

В v0.6 приложение впервые подключилось к:

`android.cartech.cardata.CarData`

Фактически было получено:

- статус: **ПОДКЛЮЧЕНО**
- listener: **активен, result=0**
- было обнаружено около **180 системных ключей**

Наблюдавшиеся реальные изменения через DIRECT CarData:

- `state.main.battery_volt.i` — напряжение, например 1380 = 13.80 V;
- `data.main.main_volume.i` — громкость;
- `state.main.headlight_on.z` — свет;
- `data.main.night_brightness_on.z` — ночной режим/яркость;
- разные Bluetooth/system state ключи.

То есть `CarData` реально работает.

### Почему двери не заработали в v0.6

v0.6 использовала неправильный вариант getter-а для CANBUS-ключей и в основном ловила общий системный namespace.

При анализе штатного APK было обнаружено, что CANBUS-свойства читаются через перегруженные методы, в частности:

- `getInt(String key, int index, int defaultValue)`
- `getString(String key, int index)`

а не только через простые:

- `getInt(String key, int defaultValue)`
- `getString(String key)`

**v0.7 уже исправлена:** она пытается использовать точные перегруженные методы и опрашивает конкретные CANBUS-ключи.

---

## 6. Ключи CarData, найденные в штатном APK

Особенно важные:

- **`state.canbus.door_state.i`**
- **`state.canbus.turn_state.i`**
- **`state.canbus.out_temp.s`**
- **`state.canbus.canbox_version.s`**
- **`factory.canbus.current_canbus.s`**

Также в системе были замечены:

- `data.canbus.out_temp_unit_is_f.z`
- `data.canbus.out_temp_show_type.i`
- `data.canbus.disable_door_float.z`
- `data.canbus.disable_air_float.z`
- `factory.canbus.front_door_swap.z`
- `factory.canbus.rear_door_swap.z`
- `factory.canbus.air_temp_swap.z`
- `factory.canbus.current_canbus.s`
- `data.canbus.air_show_time.i`
- `data.canbus.radar_show_time.i`

Это подтверждает, что платформа магнитолы имеет отдельный CANBUS namespace.

### Door-state из штатного приложения

Из анализа штатного APK следует, что его универсальная door-mask выглядит как:

- bit 0 = передняя левая
- bit 1 = передняя правая
- bit 2 = задняя левая
- bit 3 = задняя правая
- bit 4 = багажник
- bit 5 = капот

В v0.7 эта generic mask конвертируется в уже используемую внутреннюю маску CarInfo.

---

## 7. Сырой протокол между CAN-box и магнитолой

Штатный debug-screen показывает строки вида:

`2E ...`

Выведенная структура:

`2E | CMD | LEN | DATA... | CHECKSUM`

### Checksum

Подтверждено на множестве кадров:

`sum(CMD + LEN + DATA + CHECKSUM) & 0xFF == 0xFF`

То есть:

`checksum = (0xFF - sum(CMD, LEN, DATA)) & 0xFF`

Пример:

`2E 20 02 01 01 DB`

Проверка:

`20 + 02 + 01 + 01 + DB = FF`

---

## 8. Полностью расшифрованные двери в сыром RP5-кадре

Статусный кадр:

`CMD = 0x01`

Для исследованного Peugeot дверная маска находится в payload byte index 10.

Подтверждено практическими тестами:

- `0x00` — всё закрыто
- `0x08` — багажник
- `0x10` — задняя левая
- `0x20` — задняя правая
- `0x40` — водительская передняя
- `0x80` — передняя пассажирская

Комбинированные состояния тоже подтверждены:

- задняя левая + водительская = `0x10 + 0x40 = 0x50`

Это доказало, что поле является битовой маской.

---

## 9. Подрулевой пульт — расшифровка CMD 0x20

Формат события:

`2E 20 02 [eventId] [state] [checksum]`

Состояния:

- `0x01` = нажатие
- `0x02` = repeat/hold
- `0x00` = release

Практически сопоставлено по порядку действий пользователя:

- `0x01` — Volume +
- `0x02` — Volume −
- `0x05` — SOURCE
- `0x06` — колесико вверх
- `0x07` — колесико вниз
- `0x80` — кнопка БК на торце стрекозы

---

## 10. Время CAN-box

Найден CMD:

`0xC8`

Пример:

`2E C8 04 00 00 0F 22 02`

В тесте это совпадало с:

- `0F` = 15 часов
- `22` = 34 минуты

То есть в payload:

- byte 2 = hour
- byte 3 = minute

---

## 11. Наружная температура и запас хода

Фото штатного дисплея одновременно показывало:

- **660 km** запаса хода
- **15 °C** наружной температуры

В тот же период сырой status payload содержал:

`00 02 94 00 00 FF FF FF FF 0F 00 03 FF`

Очень сильное совпадение:

- `0x02 0x94` = `0x0294` = **660**
- `0x0F` = **15**

Поэтому текущая рабочая гипотеза:

- payload bytes 1..2 = запас хода, big-endian, km
- payload byte 9 = наружная температура °C

Это уже хорошо совпало с реальным штатным дисплеем, но желательно подтвердить ещё одним изменившимся значением запаса хода/температуры.

В v0.7 также пытаемся получить наружную температуру напрямую через:

`state.canbus.out_temp.s`

---

## 12. Климат

Пользователь нажимал множество кнопок климат-контроля.

В DIRECT v0.6 очевидных новых `state.canbus...` событий климата не появилось.

Возможные причины:

1. текущий SimpleSoft/PSA протокол не публикует их в CarData;
2. данные климата идут другим ключом/структурой;
3. CANBUS-приложение обрабатывает их внутри, но не публикует в общем listener namespace;
4. для Peugeot 307 этого года RP5 вообще не декодирует climate frames.

Пока **не считать климат расшифрованным**.

---

## 13. Что показывали OBD/ECU скриншоты во время теста

Для корреляции пользователь сделал скриншоты отдельного диагностического приложения.

Наблюдалось примерно:

- coolant: 67 °C → 86 °C
- oil temp: 43 °C → 58 °C
- RPM: около 736 → 672
- battery: около 14.2 V
- speed: 0 km/h

Это не означает, что эти значения уже найдены в RP5/CarData. Это были контрольные значения для будущего сопоставления.

---

## 14. История версий приложения

### v0.1
- чтение вручную сохранённых TXT логов;
- parser `2E CMD LEN DATA CHECKSUM`;
- foreground service, autostart.

### v0.2
- Accessibility Service;
- попытка читать `2E ...` прямо с экрана штатного debug-log.

### v0.3
- своя графика Peugeot сверху;
- расшифрованная door-mask;
- отображение дверей и багажника.

### v0.4
- floating overlay;
- System Probe;
- поиск системных приложений.

### v0.5
- отдельная кнопка поиска/копирования штатного CANBUS APK;
- найден `com.cartech.service.canbus`.

### v0.6
- подключение к `android.cartech.cardata.CarData`;
- listener `*`;
- подтверждена работоспособность CarData;
- двери через direct не заработали из-за неправильного варианта getter-а;
- экранный лог по-прежнему работал как fallback.

### v0.7 — текущая
- добавлены точные indexed getter-ы:
  - `getInt(String,int,int)`
  - `getString(String,int)`
- опрос:
  - `state.canbus.door_state.i`
  - `state.canbus.out_temp.s`
  - `state.canbus.turn_state.i`
  - `state.canbus.canbox_version.s`
  - `factory.canbus.current_canbus.s`
- generic door mask преобразуется в графику CarInfo;
- screen/log path сохранён как fallback.

---

## 15. Текущий главный тест для v0.7

Нужно проверить **без открытия штатного CANBUS debug-log**:

1. Завести машину.
2. Открыть CarInfo.
3. Проверить блок `DIRECT CarData`.
4. Должно быть:
   - `ПОДКЛЮЧЕНО`
   - `Exact API: OK`
5. Посмотреть:
   - `Двери direct: 0x..`
   - `Наружная t° direct: ...`
6. По очереди открыть:
   - водительскую;
   - переднюю пассажирскую;
   - заднюю левую;
   - заднюю правую;
   - багажник.
7. Проверить, меняется ли графика **без открытия штатного CANBUS debug-screen**.
8. Если не работает — сделать `Экспорт лога` и передать файл `peugeot307_diagnostic_v07_....txt`.

---

## 16. Почему floating overlay раньше перекрывался

Обычный `TYPE_APPLICATION_OVERLAY` оказался ниже штатного CANBUS debug-window.

Позже overlay был перенесён в `AccessibilityService` и создаётся как:

`TYPE_ACCESSIBILITY_OVERLAY`

Это должно давать более высокий системный слой.

Но главный приоритет — вообще уйти от необходимости держать штатный debug-log открытым.

---

## 17. Архитектура приложения сейчас

### MainActivity
- dashboard;
- визуальная машина;
- direct status;
- diagnostic/export buttons;
- Accessibility/overlay management.

### MonitorService
- foreground service;
- START_STICKY;
- запускает DirectCarDataReader;
- fallback: читает сохранённые TXT;
- поддерживает overlay fallback.

### DirectCarDataReader
Главный экспериментальный direct-reader.

Задачи:

- attach к `android.cartech.cardata.CarData`;
- listener `*`;
- direct polling ключей CANBUS;
- диагностика изменений;
- запись `direct_car_data.log`.

### CanScreenAccessibilityService
Fallback для чтения debug-log на экране.

### FrameParser
Парсит vendor frames `2E...`.

### VehicleData
Общая расшифровка:
- RP5 door-mask;
- buttons;
- clock;
- telemetry candidates.

### CarDoorView
Собственная отрисовка автомобиля и открытых дверей.

---

## 18. Build

Проект минимальный Android/Java, без Compose.

Параметры:

- compileSdk 35
- targetSdk 34
- minSdk 26
- Java 17
- Gradle 8.9
- AGP из корневого build.gradle.kts

GitHub Actions:

`.github/workflows/build-apk.yml`

Сборка:

`gradle :app:assembleDebug`

APK:

`app/build/outputs/apk/debug/app-debug.apk`

---

## 19. Автозапуск

Используется:

- `BOOT_COMPLETED`
- foreground service
- `START_STICKY`

Особенности Android:

- после force-stop приложение не получит boot receiver, пока его снова вручную не запустят;
- китайская прошивка магнитолы может иметь свои ограничения автозапуска.

---

## 20. Safety / что НЕ делать

До полного понимания протокола:

- не писать произвольные кадры в `/dev/ttyCanbus`;
- не отправлять неизвестные команды в CAN-box;
- не менять BSI settings;
- не делать активный fuzzing;
- не подключать GPIO/ESP32 напрямую к VAN без правильного automotive transceiver/protection;
- не шить случайные CTC01 firmware без точного board/MCU match и recovery backup.

Текущая линия разработки — **read-only**.

---

## 21. Что делать дальше после direct doors

Если v0.7 успешно читает `state.canbus.door_state.i`:

1. убрать зависимость от Accessibility для обычной работы;
2. оставить screen parser только как диагностический fallback;
3. найти direct ключ наружной температуры;
4. перечислить все `state.canbus.*` и `data.canbus.*` ключи;
5. отследить изменения при:
   - кнопке БК;
   - изменении наружной температуры;
   - движении автомобиля;
   - изменении остатка топлива;
   - изменении range;
6. разобрать stock APK глубже на предмет:
   - cruising range;
   - average consumption;
   - current consumption;
   - average speed;
   - speed;
   - climate structs;
7. если CarData не публикует нужные данные — следующий уровень:
   - Binder `car_server`;
   - read-only доступ к `/dev/ttyCanbus`;
   - native `libserial_port.so` / native decoder.

---

## 22. Диагностические данные, которые важно сохранить

Особенно полезные файлы из теста v0.6:

- `peugeot307_diagnostic_v06_20250715_195308(1).txt`
- `peugeot307_diagnostic_v06_20250715_195738(1).txt`

В них видно:

- DIRECT connected;
- listener result=0;
- около 180 keys;
- battery/volume/headlight changes;
- screen fallback door frames.

Если эти файлы приложены в архив рядом с исходниками — новый чат должен их использовать как фактическое подтверждение.

---

## 23. Короткий prompt для нового чата

Можно написать новому ChatGPT:

> Продолжаем проект Peugeot 307 CarInfo. В архиве лежат текущие исходники и README_FUTURE_CHAT_RU.md. Сначала прочитай README целиком и считай его handoff-документом. Текущая версия v0.7. Главная задача — добиться прямого чтения state.canbus.door_state.i через android.cartech.cardata.CarData без открытия штатного CANBUS debug-log. Не ломай уже подтверждённую расшифровку сырого протокола и сохраняй read-only подход.

---

## 24. Главное в одном абзаце

У нас уже есть рабочий Android-проект, умеющий читать raw `2E...` debug frames, полностью расшифрованные двери/багажник и подрулевые кнопки. Был найден штатный `com.cartech.service.canbus`, его внутренний `CarData` API и exact CANBUS keys. v0.6 доказала, что `CarData` доступен стороннему APK, но использовала не тот getter для CANBUS properties. v0.7 исправляет это и напрямую опрашивает `state.canbus.door_state.i` и `state.canbus.out_temp.s`. Ближайший тест — двери без открытия штатного лога. Если direct работает, следующий этап — расход/БК/температуры через те же внутренние API.
