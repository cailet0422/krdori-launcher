# 한도리 런처 (krdori-launcher)

[krdori-v2](https://github.com/NatsuFlatWhite/krdori-v2) 로컬 서버를 안드로이드 앱 안에서 돌리고,
127.0.0.1로 개조된 게임 클라이언트(`com.kakaogames.bangdreamkr.local`)를 버튼 하나로 실행하는 도구 앱입니다.
Termux 없이 동작합니다.

> 비공식 비상업 프로젝트입니다. Bushiroad, Craft Egg, Kakao Games, BanG Dream! 및 관련 권리자와 아무런 관련이 없습니다.
> 게임 APK와 공식 게임 리소스는 포함하지 않으며 배포하지 않습니다.

## 사용법

1. 런처 APK 설치 (arm64 기기, Android 7.0 이상)
2. 앱에서 **개조 APK 설치** → 127.0.0.1로 개조된 게임 APK 선택 (이미 설치되어 있으면 생략)
3. **원클릭 플레이** → 서버가 켜지면(약 5초) 게임이 자동 실행됩니다
4. 처음 카카오 로그인 시 브라우저에 `Continue` 링크가 뜨면 눌러서 게임으로 돌아갑니다
5. 다 하면 앱이나 알림에서 **서버 중지**

- 세이브 데이터: 앱 내부 `state/db.sqlite3`. **백업/복원** 버튼으로 파일로 내보내거나 되돌릴 수 있습니다.
  Termux에서 쓰던 `krdori-v2/state/db.sqlite3`도 복원으로 가져올 수 있습니다
  (옆에 `db.sqlite3-wal` 파일이 남아 있으면 최근 진행이 빠질 수 있으니, Termux에서 서버를 정상 종료한 뒤 가져오세요).
- **GitHub 최신 받기**: 서버 코드만 krdori-v2 `main` 최신으로 교체합니다(세이브 유지). 문제가 생기면 **내장 버전 복원**.
- 일부 제조사(삼성·샤오미 등)는 백그라운드 앱을 강하게 종료하므로 **배터리 최적화 제외**를 권장합니다.

## 구조

| 위치 | 역할 |
| --- | --- |
| `krdori-v2/` | 업스트림 서버 ([NatsuFlatWhite/krdori-v2](https://github.com/NatsuFlatWhite/krdori-v2)) git 서브모듈. 빌드 시 `assets/krdori-server.zip`으로 묶임 |
| `app/src/main/python/krdori_launcher.py` | 업스트림 `main.py` 대체: 3개 서버를 프로세스 대신 스레드로 실행 |
| `ServerService.kt` | `:server` 프로세스의 포그라운드 서비스. Chaquopy로 Python 실행, 중지 시 프로세스 종료 |
| `ServerFiles.kt` | 서버 파일 추출/교체, 상태 파일, 로그, DB 백업·복원 |
| `MainActivity.kt` | 원클릭 플레이 UI |

포트: Kakao `8480`(HTTP) · Session `8481`(WebSocket) · Game `8482`(HTTP)

## 빌드

필요: JDK 17 이상, Android SDK(platform 36), Python 3.12(`py -3.12` 또는 `python3.12`로 찾을 수 있어야 함 — Chaquopy 빌드용)

```bash
git clone --recursive https://github.com/cailet0422/krdori-launcher.git
cd krdori-launcher
./gradlew assembleRelease
```

결과: `app/build/outputs/apk/release/app-release.apk` (사이드로드용으로 debug 키로 서명됨)

이미 `--recursive` 없이 받았다면 `git submodule update --init`을 먼저 실행하세요.

### 내장 서버 버전 올리기

```bash
git -C krdori-v2 pull origin main
```

그 다음 `app/build.gradle.kts`의 `SERVER_REVISION`을 `git -C krdori-v2 rev-parse --short HEAD` 값으로 바꾸고 다시 빌드합니다.

## 참고 및 사용한 저장소

이 프로젝트는 아래 저장소들을 기반으로 하거나 사용합니다. 각 저장소의 라이선스는 해당 저장소를 따릅니다.

### 서버 (기반 프로젝트)

| 저장소 | 용도 | 라이선스 |
| --- | --- | --- |
| [NatsuFlatWhite/krdori-v2](https://github.com/NatsuFlatWhite/krdori-v2) | 앱에 내장되는 한도리 로컬 서버 (서브모듈) | MIT |
| [RainbowUnicorn7297/krdori-local](https://github.com/RainbowUnicorn7297/krdori-local) | krdori-v2의 원본 프로젝트 | MIT |

### 앱에 포함되는 라이브러리

| 저장소 | 용도 | 라이선스 |
| --- | --- | --- |
| [chaquo/chaquopy](https://github.com/chaquo/chaquopy) | 안드로이드 앱에 Python 3.12 내장 | MIT (Python 자체는 PSF) |
| [pallets/flask](https://github.com/pallets/flask), [pallets/werkzeug](https://github.com/pallets/werkzeug) | Game 서버 (HTTP) | BSD-3-Clause |
| [python-websockets/websockets](https://github.com/python-websockets/websockets) | Session 서버 (WebSocket) | BSD-3-Clause |
| [protocolbuffers/protobuf](https://github.com/protocolbuffers/protobuf) | 게임 API 메시지 직렬화 | BSD-3-Clause |
| [Legrandin/pycryptodome](https://github.com/Legrandin/pycryptodome) | 게임 API AES 암호화 | BSD-2-Clause / Public Domain |
| [androidx/androidx](https://github.com/androidx/androidx) | AndroidX (core, appcompat, activity, lifecycle) | Apache-2.0 |
| [material-components/material-components-android](https://github.com/material-components/material-components-android) | Material 3 UI 컴포넌트 | Apache-2.0 |
| [Kotlin/kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) | 코루틴 | Apache-2.0 |

Flask의 의존성(Jinja2, MarkupSafe, click, itsdangerous, blinker)도 함께 포함됩니다.

### Special Thanks

- krdori-v2 기여자 **Wuju_puppy** (krdori-v2 README Credits)

## 라이선스

이 저장소의 코드는 [MIT License](LICENSE)입니다.
`krdori-v2/` 서브모듈은 원본 저장소의 MIT 라이선스(`Copyright (c) 2024 RainbowUnicorn7297`, `Copyright (c) 2026 NatsuFlatWhite`)를 따릅니다.
