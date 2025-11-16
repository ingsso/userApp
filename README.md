🚨 실시간 CCTV 기반 보도블록 위 장애물 근접 경고 앱
본 프로젝트는 사용자의 실시간 위치를 Google Map에 표시하고, 
Firebase Realtime Database에 등록된 CCTV(장애물) 위치에 일정 거리 이내로 근접할 경우 사용자에게 진동과 시각적 경고를 제공하는 안드로이드 애플리케이션입니다.

(현재의 시각적 표현은 핵심 기능인 '진동 알림'을 테스트하고 검증하기 위한 목적입니다.)


![output (1)](https://github.com/user-attachments/assets/da37b7bf-87ae-4ce4-9cf0-40e6e63df206)

🛠️ 사용된 핵심 기술
- 언어: Java
- 플랫폼: Android
- 지도: Google Maps SDK
- 위치: Google Play Services Location (FusedLocationProviderClient)
- 데이터베이스: Firebase Realtime Database
- 위치 쿼리: geofire-java-core (GeoHash 유틸리티)
- 비동기 처리: ExecutorService (SingleThreadExecutor), Handler

🗺️ 주요 기능
-- 실시간 위치 추적
-> FusedLocationProviderClient를 사용하여 사용자의 현재 위치를 맵에 정확하게 표시하고, 21 레벨의 줌으로 카메라를 이동시킵니다.

-- CCTV 데이터 로드
-> Firebase Realtime Database에서 실시간으로 CCTV 위치 데이터를 불러와 지도에 마커로 표시합니다.

-- Geohash 기반 최적화
-> GeoHash를 사용하여 사용자의 현재 위치가 포함된 영역의 데이터만 효율적으로 쿼리하여, 불필요한 데이터 로드를 최소화합니다.

-- 근접 경고 시스템
-> 등록된 CCTV에 4m 이내로 접근하면 UI 경고 아이콘이 활성화됩니다.
-> 거리에 따라(4m, 2m) 차등화된 진동 패턴을 제공하여 사용자에게 위험 근접을 알립니다.

-- 실시간 거리 표시
-> 가장 가까운 CCTV까지의 거리를 TextView에 실시간으로 표시합니다.

-- 백그라운드 스레드 처리
-> ExecutorService를 사용하여 거리 계산 로직을 백그라운드 스레드에서 처리, UI 스레드의 부하를 줄여 부드러운 앱 경험을 제공합니다.

⚙️ 아키텍처 및 동작 원리
1. 위치 업데이트
-> FusedLocationProviderClient가 0.5초(setInterval(500)) 간격으로 사용자의 위치를 감지합니다.

2. Geohash 생성
-> 현재 위치(lat, lng)를 기반으로 6자리 정밀도의 GeoHash 문자열을 생성합니다. (예: "9q9j6j")

3. Firebase 접근
-> GeoHash 값이 변경될 때마다 기존 Firebase 리스너를 해제하고, 새 GeoHash에 해당하는 경로에 해당하는 파일에 접근합니다.
(subscribeToCCTVs() 메서드가 이 로직을 담당합니다.)

4. 데이터 캐싱 및 거리 계산
-> 근접한 경로의 CCTV 데이터를 불러와 300m 이내의 CCTV만 cachedCCTVList에 저장합니다.
-> calculationExecutor (별도 스레드)가 0.1초(MIN_CALCULATION_INTERVAL)마다 cachedCCTVList의 데이터를 기반으로 현재 위치와의 거리를 Haversine 공식(haversineDistance())을 이용해 계산합니다.
-> isCalculating 플래그를 사용하여 중복 계산을 방지합니다.

5. UI 및 진동 피드백
-> 계산된 거리가 4m 이하일 경우, mainHandler (메인 스레드)를 통해 UI를 업데이트(아이콘 표시, 텍스트 변경 : 테스트 용)하고 Vibrator를 실행합니다.
-> 근접 거리에 따라(4m, 2m) 진동 패턴이 달라집니다.
-> 사용자가 범위(4m)를 벗어나면 진동과 아이콘이 자동으로 중지/해제됩니다.

🗄️ Firebase 데이터베이스 구조
-> 이 앱이 정상적으로 동작하기 위해 Firebase Realtime Database는 반드시 아래와 같은 Geohash 기반의 구조를 가져야 합니다. 
-> obstacles 노드 아래에 6자리 Geohash 문자열을 키(key)로 사용하고, 그 하위에 각 CCTV의 데이터를 저장합니다.
(테스트 시 보안 규칙을 read: true, write: true로 설정합니다.)

```json
{
  "obstacles": {
    "9q9j6j": {
      "cctv_key_001": {
        "latitude": 37.5665,
        "longitude": 126.9780
      },
      "cctv_key_002": {
        "latitude": 37.5666,
        "longitude": 126.9781
      }
    },
    "9q9j6k": {
      "cctv_key_003": {
        "latitude": 37.5670,
        "longitude": 126.9790
      }
    }
  }
}
```
