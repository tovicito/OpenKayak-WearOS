## 2025-05-20 - Fast Distance Calculation in Circuit Analysis

**Learning:** `Location.distanceBetween` from Android's framework allocates float arrays and uses expensive geodesic Vincenty/Haversine calculations on WGS84 ellipsoid. During `CircuitLearner` analysis across hundreds/thousands of GPS track points, thousands of pairwise distance calls are performed.
**Action:** Use an Equirectangular projection approximation for local distance calculations (< 100km). It is orders of magnitude faster, avoids JNI/framework overhead, and is accurate within < 0.1% for local kayak routes.
