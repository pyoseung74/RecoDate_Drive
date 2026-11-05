# RecoDate Drive (레코데이트 드라이브)

**목적지만 정해 두면 차 안에서 바로 "어디에 차를 대고, 어떤 순서로 돌지"까지 코스를 짜 주고, Pleos 내비가 그 주차장으로 안내하게 만드는 나들이·데이트 코스 앱입니다.**

현대자동차그룹 차량용 앱 개발 공모전(Pleos Connect) 출품작입니다. 팀 DeltaLAB (팀장 김표승, 팀원 박정우·정의영·이용근).

## APK 파일

| 항목 | 내용 |
|---|---|
| 위치 | [`apk/DeltaLAB_RecoDateDrive_release.apk`](apk/DeltaLAB_RecoDateDrive_release.apk) |
| 빌드 | release (디버그 기능 꺼짐), versionName 0.1.0, 패키지 `com.recodate.drive` |
| 서명 | 별도 설치를 위해 Android 디버그 인증서로 서명 (Pleos App Market 배포 시 정식 키로 다시 서명) |
| 크기 | 약 46MB |

## 시연 영상

[`demo/DeltaLAB_RecoDateDrive_시연영상.mp4`](demo/DeltaLAB_RecoDateDrive_시연영상.mp4) (약 2분, 1920×1080, 한국어 자막·음성 안내 포함)

Pleos Connect 에뮬레이터에서 실제 데이터로 녹화했습니다. 내비 목적지 자동 선택 → 코스 추천 → "차로 이동" 코스 → 비 오는 날 → 안내 시작(Pleos 내비) → 주차장 도착 순서입니다. 자막 파일: [`demo/DeltaLAB_RecoDateDrive_시연영상.srt`](demo/DeltaLAB_RecoDateDrive_시연영상.srt). 영상 제작 스크립트: [`docs/video/`](docs/video/)

## 실행 방법

1. Pleos Connect 에뮬레이터 v2.1.0을 실행합니다. (아래 "테스트 환경" 참고)
2. APK를 설치합니다. `-g`는 앱이 쓰는 Pleos 권한을 함께 허용합니다.
   ```
   adb install -r -g apk/DeltaLAB_RecoDateDrive_release.apk
   ```
3. 앱 목록에서 **RecoDate Drive**를 엽니다.
4. 출발 화면에서 목적지를 고르고 **코스 추천 받기**를 누릅니다.
   - 내비에 목적지를 설정해 두었다면 그 목적지가 맨 위에 자동으로 선택됩니다.
   - 최근 목적지·즐겨찾기, "지금 내 주변"도 고를 수 있습니다.
5. 추천 코스(최대 3개) 중 하나에서 **이 코스로 안내 시작**을 누르면 Pleos 내비가 추천 주차장으로 경로 안내를 시작합니다. 내비가 실제로 그 목적지 안내를 시작한 것이 확인되면 앱 화면이 "안내 중"으로 바뀝니다.
6. 도착하면 걸어서 둘러볼 장소 단계로 넘어갑니다. "차로 이동" 코스에서는 다시 출발하면 다음 장소 주차장 안내를 자동으로 요청합니다.

### 시연 패널

오른쪽 위 플라스크 아이콘을 누르면 심사·시연용 상황을 바꿀 수 있습니다.

- 비 오는 날 (비 예보로 바꿔 "차로 이동"·실내 위주 추천 확인)
- 배터리 12% (배터리 부족 경고 확인)
- 내비 도착 이벤트 보내기
- Pleos 차량이 아닐 때는 주행 중(기어 D) 전환도 할 수 있습니다. Pleos 에뮬레이터에서는 Virtual Console의 기어 값을 따릅니다.
- TMAP 오늘·이번 달 사용량

## 테스트 환경 및 필요한 설정

| 항목 | 내용 |
|---|---|
| 확인한 환경 | Pleos Connect Emulator v2.1.0 (Android 14, API 34, Automotive, x86_64, 2560×1440), Windows 11 |
| 앱 요구사항 | minSdk 28, targetSdk 34 |
| 네트워크 | 인터넷 연결 필요 (장소·주차장·교통·날씨). 연결이 없으면 내장 데모 데이터(강릉)로 동작 |
| 사용하는 Pleos SDK | Vehicle 2.3.2 (기어, EV 배터리, 연료), NaviHelper 3.0.5 (목적지·위치 읽기, 경로 요청, 안내·도착 이벤트) |
| 권한 | `pleos.car.permission.CAR_DRIVE`, `CAR_ENERGY`, `NAVI_ROUTE`, `NAVI_ROUTE_SEARCH` |
| 외부 데이터 | TMAP(장소·주차장 검색, 실시간 교통 경로), 기상청 단기예보. 제출용 APK에는 키가 들어 있어 별도 설정 없이 동작 |

**에뮬레이터에서 확인할 점**

- **Pleos 내비가 "부팅 중"에서 멈추거나 경로 안내가 안 되면** 에뮬레이터의 DNS 문제일 수 있습니다. `-dns-server 8.8.8.8` 옵션으로 에뮬레이터를 실행하면 해결됩니다. (이 저장소의 `run_pleos_emulator.bat` 참고)
- **에뮬레이터의 Pleos 내비는 가까이 확대한 지도 배경이 표시되지 않고** 경로선과 안내 정보만 보입니다(전체 경로 미리보기에서는 지도가 보입니다). 경로 계산·교통 정보·도착 시간은 정상으로 동작하며, 앱 동작과는 무관합니다.
- 기어·배터리 변화는 Pleos Virtual Console(`adb forward tcp:8088 tcp:8088` 후 http://127.0.0.1:8088)로 바꿀 수 있습니다. AVD는 Cold boot로 실행해야 차량 데이터(VHAL)를 쓸 수 있습니다.
- 에뮬레이터의 현재 위치는 판교로 잡혀 있어, 먼 목적지는 도착 시간이 길게 나옵니다.

**TMAP 사용량 보호**

외부 API 키가 앱에 들어 있으므로, 무료 사용량을 넘지 않도록 앱 안에 상한을 두었습니다. 제출용 APK의 하루 상한은 장소 검색 500건, 경로 30건이며 한 달 상한도 따로 있습니다. 상한에 닿으면 TMAP을 부르지 않고 예상 시간과 데모 데이터로 대신합니다. (자세한 내용: [docs/API_KEYS.md](docs/API_KEYS.md))

## 소스에서 빌드하기

1. Android Studio(최신)로 이 폴더를 엽니다. Pleos SDK는 `settings.gradle.kts`의 Pleos Maven 저장소에서 받습니다.
2. `local.properties`에 API 키를 넣습니다(선택). 키가 없으면 강릉 데모 데이터로 동작합니다. → [docs/API_KEYS.md](docs/API_KEYS.md)
3. `gradlew assembleRelease` (결과: `app/build/outputs/apk/release/app-release.apk`)

- 단위 테스트: `gradlew testDebugUnitTest` (44개, 재생 데이터가 없으면 재생 테스트 1개는 건너뜀)
- Windows 사용자 폴더 이름에 한글이 있으면 테스트 실행과 에뮬레이터 부팅이 실패할 수 있습니다. 영문 경로(예: `subst P: ...`)와 `GRADLE_USER_HOME=C:\Android\gradle-home`을 쓰면 됩니다.

## 구조

```
app/src/main/java/com/recodate/drive/
  engine/   추천 엔진 (Android에 의존하지 않는 순수 Kotlin): CourseRecommender, ParkingPredictor, Geo
  data/     TMAP·기상청·데모 데이터, TMAP 사용량 보호(TmapQuota), CourseService
  vehicle/  Pleos Vehicle SDK 연결 (+ Pleos가 아닐 때 데모)
  navi/     Pleos NaviHelper SDK 연결 (+ 데모)
  ui/       출발 / 추천 / 실행 화면, ViewModel
```

- 설계: [docs/DESIGN.md](docs/DESIGN.md)
- 테스트 방법: [docs/TESTING.md](docs/TESTING.md)
- 출품 전 검증 결과·남은 확인·발전 우선순위: [docs/PRE_SUBMISSION_REVIEW.md](docs/PRE_SUBMISSION_REVIEW.md)
