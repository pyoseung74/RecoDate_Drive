# 테스트 방법

## 1. 단위 테스트

```
P:\RecoDate_Pleos\gradlew.bat testDebugUnitTest
```

(한글 경로 문제로 `subst P:`와 `GRADLE_USER_HOME=C:\Android\gradle-home`을 쓴다. README 참고.)

- `CourseRecommenderTest`: 강릉 데모 데이터로 추천 엔진 규칙 확인
- `CourseServiceTest`: 목적지 이름 맞추기, 주차장·주민 전용 주차장 판별
- `TmapQuotaTest`: TMAP 무료 한도 보호
- `SnapshotReplayTest`: 실제 데이터 녹화본으로 전 조합 검사 (아래)

## 2. 실제 데이터 녹화·재생 테스트

TMAP 무료 한도를 아끼려고, 목적지마다 실제 데이터를 **한 번만** 받아 저장한 뒤
같은 데이터로 추천 엔진을 API 호출 없이 반복 검사한다.

### 녹화 (디버그 빌드 + 에뮬레이터)

디버그 빌드는 추천할 때마다 입력 데이터(장소·주차장·날씨)를 앱의 `files/snapshots`에 저장한다.
adb로 목적지를 넣어 바로 추천할 수 있다. 이름만 주면 TMAP 장소 검색 1건으로 좌표를 찾는다.
이 진입점은 경로(교통) 호출을 하지 않는다.

```
adb shell am start -n com.recodate.drive/.MainActivity --es anchor "'삼척해수욕장'" --ez named true --es mode PARK_AND_WALK
adb shell am start -n com.recodate.drive/.MainActivity --es anchor "'판교 내 주변'" --ez named false --ed lat 37.4127 --ed lon 127.0947 --es mode DRIVE_AND_HOP
```

- `named true`: 내비 목적지처럼 이름 있는 목적지 (코스에 꼭 넣음), `false`: "내 주변"처럼 중심점만
- 목적지 1곳·모드 1개당 TMAP 사용량: POI 약 7건, 경로 0건

녹화본 가져오기 (에뮬레이터는 사용자 10으로 앱을 실행한다):

```
adb shell run-as com.recodate.drive --user 10 ls files/snapshots
adb exec-out run-as com.recodate.drive --user 10 cat files/snapshots/<파일> > testdata/snapshots/<파일>
```

**`testdata/`는 git에 올리지 않는다.** TMAP 응답 데이터가 들어 있기 때문이다.

### 재생 (`SnapshotReplayTest`)

녹화본마다 관심사 6가지 × 날씨 2가지(그대로·비) × 시간대 4가지 × 배터리 2가지 = 96개 조합을 돌려 검사한다.

- 코스가 1개 이상 나온다
- 이름 있는 목적지는 모든 코스에 들어 있다
- 주차장이 장소로 섞이지 않는다
- 방문객이 못 쓰는 주차장(거주자 우선·아파트·전용)을 쓰지 않는다
- 도보 제한: 한 번 주차 총 2km, 차로 이동 주차장↔장소 260m (목적지는 700m). 완화 단계는 1.7배이며 화면에 표시된다
- 한 코스 안에 같은 장소가 두 번 나오지 않고, 같은 코스가 반복되지 않는다

녹화본이 없으면 이 테스트는 건너뛴다.

### 2026-10-30 녹화한 목적지 (11곳 × 2개 모드 = 22개, 2,112개 조합)

삼척해수욕장, 강남역, 불국사, 전주한옥마을, 해운대해수욕장, 스타필드 하남, 설악산 소공원,
대관령 양떼목장, 협재해수욕장, 안목해변 공영주차장(주차장 목적지), 판교 내 주변

고치기 전에는 문제 1,016건이 나왔다 (목적지 누락 858, 주민 전용 주차장 156, 코스 0개 2개 목적지).
지금은 0건이다.
