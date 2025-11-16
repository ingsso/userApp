package com.example.usertestmap;

public class KalmanFilter {
    private double q = 0.0001; // 프로세스 노이즈
    private double r = 0.1;    // 측정 노이즈
    private double x = 0;       // 추정값
    private double p = 1;       // 추정 오차
    private double k;           // 칼만 이득

    public double update(double measurement) {
        // 예측
        p = p + q;

        // 칼만 이득 계산
        k = p / (p + r);

        // 업데이트
        x = x + k * (measurement - x);
        p = (1 - k) * p;

        return x;
    }

    public void reset(double initial) {
        x = initial;
        p = 1;
    }
}
