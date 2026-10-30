# RecoDate Drive (Pleos Connect 차량용 앱)

차 안에서 고민 없이 빠르게, 목적지 주변의 장소와 코스를 추천하는 앱이다.
주차장 혼잡도, 차 막힘, 날씨, 배터리·연료를 함께 따진다.
현대자동차그룹 차량용 앱 개발 공모전 출품용이다.

## 핵심 기능

| 기능 | 설명 | 쓰는 Pleos SDK |
|---|---|---|
| 목적지 고르기 (입력 없음) | 내비 목적지, 최근 목적지, 즐겨찾기, 지금 내 주변 | NaviHelper `getDestinationInfo`, `getRecentDestinationInfo`, `getBookmarkInfo`, `getCurrentLocationInfo` |
| 코스 방식 2가지 | **한 번 주차**: 주차 1번, 걸어서 돌기 / **차로 이동**: 장소마다 앞 주차장으로 이동, 도보 최소화 | - |
| 주차장으로 바로 안내 | 내비 목적지를 장소가 아니라 주차장으로 설정 | NaviHelper `requestRoute` |
| 도착하면 다음 단계 | 도착 이벤트를 받아 걸어서 둘러보기, 또는 다음 장소 준비 | NaviHelper `onDestinationArrived` |
| 다시 출발하면 다음 장소 자동 안내 | 차로 이동형에서 기어 P → D가 되면 다음 주차장 안내를 시작 | Vehicle `Gear.registerGearSelection` |
| 주행 중 간단 모드 | 기어가 P가 아니면 1순위 코스와 큰 버튼만 표시 | Vehicle `Gear` |
| 배터리·연료 부족 안내 | 15% 이하이면 가까운 충전소·주유소 안내 | Vehicle `EvBattery`, `Fuel` |
| 날씨 반영 | 비·한파·폭염이면 실내 위주, 자동으로 차로 이동형 | 기상청 단기예보 |
| 교통 반영 | 첫 주차장까지 실시간 교통 도착 시간 | TMAP 자동차 경로 |

## 실행 방법

1. Pleos Connect 에뮬레이터를 켠다: `run_pleos_emulator.bat` 더블클릭.
   (한글 사용자 폴더 문제를 피하려고 임시 드라이브 S:, T:를 쓴다.)
2. Android Studio로 이 폴더를 열고 Run을 누른다.
   - 명령줄로 빌드할 때는 한글 경로 문제를 피하려고 영문 드라이브에서 실행한다:
     `subst P: "%USERPROFILE%\Desktop\개인작업"` 후 `P:\RecoDate_Pleos\gradlew.bat assembleDebug`
     (`GRADLE_USER_HOME=C:\Android\gradle-home` 권장)
3. API 키 넣기 (선택): [docs/API_KEYS.md](docs/API_KEYS.md). 키가 없으면 강릉 데모 데이터로 동작한다.

- minSdk 28, targetSdk 34, Kotlin + Jetpack Compose
- Pleos SDK: Vehicle 2.3.2, NaviHelper 3.0.5
- 단위 테스트: `gradlew testDebugUnitTest` (추천 엔진 9개)

## 시연 패널

오른쪽 위 플라스크 아이콘을 누르면 열린다. 심사·발표 때 상황을 바꿀 수 있다.

- 주행 중 전환 (데모 차량일 때. Pleos 에뮬레이터에서는 Virtual Console의 기어를 따른다)
- 비 오는 날
- 배터리 12%
- 내비 도착 이벤트 보내기

## 구조

```
app/src/main/java/com/recodate/drive/
  engine/   추천 엔진 (순수 Kotlin, 단위 테스트): CourseRecommender, ParkingPredictor, Geo
  data/     TMAP·기상청·데모 데이터 출처, CourseService (데이터 모으기 + 실시간 교통 반영)
  vehicle/  Pleos Vehicle SDK 연결 (+ 데모)
  navi/     Pleos NaviHelper SDK 연결 (+ 데모)
  ui/       출발 / 추천 / 실행 화면, ViewModel
```

설계 문서: [docs/DESIGN.md](docs/DESIGN.md)
