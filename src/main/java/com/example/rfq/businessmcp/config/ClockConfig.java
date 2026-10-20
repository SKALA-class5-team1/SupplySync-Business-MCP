/*
 * [설정] 현재 시각(Clock) 빈. 시간대 Asia/Seoul.
 * check_parts_supply에서 base_date를 생략했을 때 '오늘'을 정하는 기준. 테스트에서 고정 시각으로 바꿀 수 있게 빈으로 둔다.
 */
package com.example.rfq.businessmcp.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfig {
    /** base_date 기본값(오늘) 계산 기준. */
    @Bean
    Clock clock() {
        return Clock.system(ZoneId.of("Asia/Seoul"));
    }
}
