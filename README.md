---
# ClockApp — BLE-шлюз для ESP32   
Android-приложение на Kotlin с Jetpack Compose, которое собирает системную информацию с телефона и передаёт её на ESP32 по Bluetooth Low Energy через собственный бинарный протокол.   
 --- 
## Функционал   
### Сбор данных с телефона   
- **Unix-время** — текущее время в секундах   
- **VPN-статус** — подключён ли VPN в данный момент   
- **Медиа-информация** — артист, название трека и статус воспроизведения (читается из уведомлений Яндекс.Музыки через `NotificationListenerService`)   
   
### BLE-соединение   
- Подключение к ESP32   
- Автоматическое расширение MTU до 512 байт   
- Очередь исходящих пакетов с учётом ограничений BLE (одна запись за раз)   
   
### Бинарный протокол   
Формат пакета:   
|         Поле |            Размер |                                                       Описание |
|:-------------|:------------------|:---------------------------------------------------------------|
|        flags |            1 байт |                                       MORE\_FRAGMENTS, REQUEST |
|      msg\_id |           2 байта |                             ID сообщения для сборки фрагментов |
| frag\_offset |           2 байта |                                             Смещение фрагмента |
| payload\_len |           2 байта |                                        Длина полезной нагрузки |
|      payload |       до 500 байт |                                              Тегированные поля |

Теги данных:   
- `0x01` — Unix-время (uint32, big-endian)   
- `0x02` — VPN-статус (bool: 0/1)   
- `0x03` — Воспроизведение (bool: пауза/играет)   
- `0x04` — Имя артиста (string: 2 байта длины + UTF-8)   
- `0x05` — Название трека (string: 2 байта длины + UTF-8)   
   
Поддержка фрагментации: сообщения &gt; 500 байт автоматически разбиваются на несколько BLE-пакетов.   
 --- 
## Скриншоты   
<img width="609" height="1280" alt="image" src="https://github.com/user-attachments/assets/25088f9a-aab3-4590-81c1-4da5db631472" />
<img width="609" height="1280" alt="image_y" src="https://github.com/user-attachments/assets/936ee964-29fd-43f2-aec9-29af03b615bc" />

 --- 
## Требования   
- Android 8.0+ (API 26)   
- Bluetooth LE   
- Разрешения: `BLUETOOTH\_SCAN` , `BLUETOOTH\_CONNECT` , `ACCESS\_FINE\_LOCATION` 
   
- Доступ к уведомлениям (для медиа-информации)
   
 --- 
   
## Сборка   
1. Запустите Android Studio   
2. На стартовом экране выберите "Clone Repository"
<img width="808" height="663" alt="image_o" src="https://github.com/user-attachments/assets/5a5586e4-7865-4521-b9c6-be1530fd0d62" />
3. Вставьте URL-адрес репозитория в поле URL
4. Укажите директорию, куда клонировать проект (Directory)
5. Нажмите Clone   
<img width="812" height="662" alt="image_h" src="https://github.com/user-attachments/assets/ceb2d2c7-d2bc-425b-b512-59646e3d7540" />
6. Дождитесь загрузки проекта. Нажмите кнопку "Run app" (Shift + F10)
<img width="1395" height="994" alt="bez-imeni" src="https://github.com/user-attachments/assets/d5bbfda4-99cd-42b7-8d0d-62e39761e3e7" />
   
 
