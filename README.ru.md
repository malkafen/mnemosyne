<!-- Language switcher -->
[English](README.md) | **Русский**

# Mnemosyne

> Декларативный, в духе Terraform, провижининг виртуальных машин libvirt/KVM на основе одного
> YAML-инвентаря и cloud-init.

Mnemosyne читает один YAML-файл — **желаемое состояние**: какие виртуальные машины должны
существовать на каких гипервизорах. Затем читает **текущее состояние** (домены, которые реально
существуют на каждом хосте) и применяет разницу:

- ВМ, которые есть в желаемом состоянии, но отсутствуют в текущем, **создаются** (диск клонируется
  из базового облачного образа, затем машина загружается и настраивается через cloud-init);
- ВМ, которые есть в текущем состоянии, но уже отсутствуют в желаемом, **уничтожаются** (домен
  снимается с определения, его дисковые тома удаляются).

С каждым гипервизором инструмент общается по `qemu+ssh` (libvirt), а данные cloud-init отдаёт только
что загрузившимся ВМ через небольшой встроенный HTTP-сервер.

---

## Содержание

- [Как это работает](#как-это-работает)
- [Понятия и термины](#понятия-и-термины)
- [Требования](#требования)
- [Сборка](#сборка)
- [Запуск](#запуск)
- [Конфигурация](#конфигурация)
- [Шаблоны cloud-init](#шаблоны-cloud-init)
- [Пример вывода](#пример-вывода)
- [Структура проекта](#структура-проекта)

---

## Как это работает

```
            ┌──────────────────────────── Mnemosyne (рабочая станция / CI) ────────────────────────────┐
            │                                                                                           │
servers.yml ─▶ загрузка+валидация ─▶ qemu+ssh ─▶ план (diff) ─▶ создание дисков ─▶ запуск доменов      │
            │                          │            │                                    │              │
            │                          ▼            ▼                                    ▼              │
            │                   хост libvirt   создать / удалить                  CloudInitServer       │
            │                   (KVM/QEMU)       решение                          HTTP :8080            │
            └─────────────────────────────────────────────────────────────────────────┬──────────────┘
                                                                                        │ ds=nocloud
                                                                                        ▼
                                                                             новая ВМ забирает user-data,
                                                                             meta-data, network-config
```

Запуск — это один проход (повторяет `Mnemosyne.run`):

1. **Загрузка и валидация желаемого состояния** — инвентарь разбирается в `List<Mnemon>`, каждое
   поле проверяется через Jakarta Bean Validation. Любое некорректное поле прерывает запуск с точным
   сообщением.
2. **Запуск cloud-init сервера** — встроенный HTTP-сервер поднимается на порту **8080** по пути
   `/cloud-init`.
3. **Подключение** — для каждой группы гипервизора открывается соединение libvirt по
   `qemu+ssh://user@host:port/system?keyfile=…`.
4. **План** — **желаемое состояние** (инвентарь) сравнивается с **текущим состоянием** (уже
   существующие на хосте домены) и печатается diff в стиле Terraform (`+ создать`, `- удалить`). С
   флагом `--plan` запуск на этом останавливается.
5. **Окно подтверждения** — пауза 10 секунд (`Ctrl+C` для отмены) перед применением изменений.
6. **Создание дисков** — для каждой новой ВМ диск клонируется из базового облачного образа
   (`volLookup`) в целевом пуле хранения и увеличивается до запрошенного размера. При сбое частично
   созданный том откатывается.
7. **Определение и запуск доменов** — формируется XML домена (имя, RAM, vCPU, диск, сеть и серийный
   номер NoCloud для cloud-init), `user-data` / `network-config` ВМ регистрируются в HTTP-сервере,
   домен определяется и запускается.
8. **Ожидание cloud-init** — опрос, пока каждая ВМ не заберёт свою конфигурацию (или таймаут ~100 с:
   20 попыток × 5 с).
9. **Сверка (reconcile)** — домены, присутствующие на хосте, но отсутствующие в инвентаре,
   уничтожаются, снимаются с определения, их диски удаляются.
10. **Завершение** — освобождаются хэндлы доменов, закрываются соединения libvirt, останавливается
    HTTP-сервер.

Связка с cloud-init использует источник данных **NoCloud**: каждой ВМ задаётся серийный номер SMBIOS
`ds=nocloud;s=<metaUrl><name>/`, который указывает cloud-init внутри гостя, откуда забирать
`meta-data`, `user-data` и `network-config`. Эти запросы попадают на HTTP-сервер Mnemosyne, который
отвечает данными для конкретной ВМ, зарегистрированными на шаге 7.

Готовая диаграмма активности лежит в [`diagrams/`](diagrams/) (`Mnemosyne Activity Diagram.png`).

---

## Понятия и термины

| Термин | Значение |
| --- | --- |
| **Mnemon** | Одна **группа гипервизора**: хост libvirt плюс список ВМ, которые должны на нём жить. Элемент верхнего уровня в инвентаре. |
| **Server** | Одна **виртуальная машина** (домен libvirt), принадлежащая Mnemon'у. |
| **Plan** | Diff создания/удаления между желаемым состоянием (инвентарь) и текущим состоянием (существующие домены). |
| **CloudInitServer** | Встроенный HTTP-сервер (`:8080/cloud-init/<vm>/<file>`), отдающий данные cloud-init загружающимся ВМ. |
| **volLookup** | Имя базового облачного образа в пуле, из которого клонируются новые диски. |

---

## Требования

На машине, которая **запускает** Mnemosyne:

- **Java 17+**
- **Maven** (для сборки)
- **Клиентские библиотеки libvirt + JNA**: `libvirt0`, `libvirt-clients`, `libvirt-dev`
- **Клиент OpenSSH** (для транспорта `qemu+ssh`)
- SSH-ключ, дающий доступ к каждому гипервизору под указанным `user`

На каждом **хосте-гипервизоре**:

- libvirt + KVM/QEMU, доступный по SSH
- Пул хранения с **базовым облачным образом**, на который ссылается `volLookup`
  (например, `debian-13-genericcloud-amd64-*.qcow2` или `noble-server-cloudimg-amd64.img`)
- Сеть libvirt (или мост), соответствующая полю `network`
- HTTP-сервер Mnemosyne (порт 8080) должен быть доступен **из ВМ** по адресу, указанному в `metaUrl`

---

## Сборка

```bash
mvn clean package
```

Создаётся объединённый (shaded) uber-jar: `target/mnemosyne-<версия>.jar`.

Форматирование кода перед push:

```bash
find src/main/java -name "*.java" | xargs java -jar google-java-format-*-all-deps.jar -i
```

---

## Запуск

```bash
# Только предпросмотр изменений (ни одна ВМ не затрагивается):
java -jar target/mnemosyne-*.jar --servers-file ./configs/servers.yml --plan

# Применение (10 с на подтверждение перед изменениями):
java -Djna.library.path=/usr/lib/x86_64-linux-gnu \
     -jar target/mnemosyne-*.jar --servers-file ./configs/servers.yml
```

> `-Djna.library.path` указывает JNA на нативную библиотеку libvirt. Типичные значения:
> `/usr/lib/x86_64-linux-gnu` (Debian/Ubuntu), `/usr/lib64` (RHEL/Fedora),
> `/opt/homebrew/lib` (macOS / Homebrew).

### Флаги CLI

| Флаг | Описание | По умолчанию |
| --- | --- | --- |
| `--servers-file <path>` | Путь к YAML-инвентарю | `/etc/mnemosyne/servers.yml` |
| `--plan` | Только план — напечатать diff и выйти без изменений | выкл. |

### Docker

Многоступенчатый [`Dockerfile`](Dockerfile) собирает jar и runtime-образ с клиентскими библиотеками
libvirt и встроенной папкой `templates/`:

```bash
docker build -t mnemosyne .
docker run --rm \
  -v "$PWD/configs:/app/configs" \
  -v "$HOME/.ssh:/root/.ssh:ro" \
  mnemosyne --servers-file /app/configs/servers.yml
```

---

## Конфигурация

Инвентарь — это YAML-**список Mnemon'ов** (групп гипервизоров). Полностью прокомментированный
стартовый файл лежит в [`configs/servers.example.yml`](configs/servers.example.yml) — скопируйте и
отредактируйте:

```bash
cp configs/servers.example.yml configs/servers.yml
```

### Поля Mnemon (группа гипервизора)

| Поле | Обязательно | Описание |
| --- | --- | --- |
| `group` | да | Человекочитаемая метка группы (в логах и выводе плана). |
| `host` | да | Адрес гипервизора для соединения SSH/libvirt. |
| `user` | да | SSH-пользователь на гипервизоре. |
| `port` | да | SSH-порт (1–65535). |
| `key` | — | Путь к **приватному** SSH-ключу на машине, запускающей Mnemosyne. |
| `servers` | да (≥1) | Список ВМ, которые должны существовать в этой группе. |

### Поля Server (ВМ)

| Поле | Обязательно | По умолчанию | Описание |
| --- | --- | --- | --- |
| `name` | да | — | Имя ВМ. Используется как имя домена libvirt, имя дискового тома **и** hostname. |
| `cpu` | да | `2` | Количество vCPU (целое положительное, строкой). |
| `ram` | да | `1024` | Память в **MiB** (целое положительное, строкой). |
| `ip` | да | — | Адрес в нотации **CIDR**, например `192.168.70.70/24`. |
| `gateway` | — | — | Шлюз по умолчанию (обычный IPv4). |
| `disk` | — | `30` | Размер диска в **GiB** (мин. 10). Клонированный образ увеличивается до этого. |
| `pool` | да | `default` | Пул хранения libvirt с базовым образом и новым диском. |
| `volLookup` | — | `noble-server-cloudimg-amd64.img` | Базовый образ в `pool`, из которого клонируется диск. |
| `network` | да | `default` | Имя сети / моста libvirt для подключения ВМ. |
| `metaUrl` | да | `http://127.0.0.1:80/files/` | Базовый URL, по которому ВМ обращается к cloud-init серверу Mnemosyne. Задайте `http://<хост-mnemosyne>:8080/cloud-init/`. |
| `launch` | — | `true` | Нужно ли запускать ВМ. |
| `serverTmpl` | — | `/app/templates/server.xml` | Шаблон XML домена. |
| `volTmpl` | — | `/app/templates/volume.xml` | Шаблон XML тома. |
| `userDataTmpl` | — | `/app/templates/user-data.yml` | Шаблон user-data для cloud-init. |
| `networkConfigTmpl` | — | `/app/templates/network-config.yml` | Шаблон network-config для cloud-init. |

> **Важно:** `metaUrl` должен быть доступен **изнутри ВМ**. `127.0.0.1` сработает, только если гость
> и Mnemosyne в одном сетевом пространстве; обычно нужен IP хоста с Mnemosyne, доступный с
> гипервизора, плюс порт `8080` и путь `/cloud-init/`.

---

## Шаблоны cloud-init

При создании ВМ Mnemosyne рендерит два документа cloud-init из шаблонов и отдаёт их по HTTP:

- **user-data** — пакеты, пользователи, SSH-ключи, sysctl и т. п. `hostname`/`fqdn` заполняются из
  `name` сервера. См. [`templates/user-data.example.yml`](templates/user-data.example.yml).
- **network-config** — интерфейс `vif0` получает `addresses` и `gateway4` из `ip`/`gateway`
  сервера. См. [`templates/network-config.example.yml`](templates/network-config.example.yml).

Файлы `.example.yml` закоммичены как образец. Скопируйте их в рабочие имена и добавьте свои
публичные SSH-ключи:

```bash
cp templates/user-data.example.yml      templates/user-data.yml
cp templates/network-config.example.yml templates/network-config.yml
# затем отредактируйте templates/user-data.yml — замените placeholder в ssh_authorized_keys своими ключами
```

---

## Пример вывода

```
[ hv01.example.lan ]  create: 2, delete: 1
  + web-01.example.lan
  + cache-01.example.lan
  - old-test.example.lan

Applying in 10s — Ctrl+C to abort...
10:42:07 Connection to 'hv01.example.lan' was successful.
10:42:19 Domain 'web-01.example.lan' has been started successfully.
10:42:21 Domain 'cache-01.example.lan' has been started successfully.
10:42:21 All 2 mnemones provisioned. Waiting cloud-init is done...
10:43:35 All cloud-init tasks completed (2/2). Preparing for shutdown...
```

---

## Структура проекта

```
src/main/java/com/mnemosyne/app/
  Mnemosyne.java                 # точка входа и оркестрация (цикл run выше)
  config/Config.java             # разбор аргументов CLI
  model/Mnemon.java              # группа гипервизора: connect, plan, диски, домены, reconcile
  model/Server.java              # одна ВМ: рендеринг XML/YAML-шаблонов, валидация
  model/Plan.java                # diff создания/удаления
  model/Status.java              # статус жизненного цикла сервера
  model/DomainInspector.java     # чтение путей дисков из XML существующего домена
  http/CloudInitServer.java      # встроенный HTTP-сервер для данных cloud-init
templates/                       # XML доменов/томов + YAML-шаблоны cloud-init
configs/                         # инвентарь + конфигурация logback
diagrams/                        # диаграммы активности и последовательности
```

---

## Лицензия

Apache License 2.0 — см. [`LICENSE`](LICENSE) и [`NOTICE`](NOTICE).
