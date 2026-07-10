package com.shuimumu.fuluogame.utils;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class RedisData {
    private Object data;
    private LocalDateTime expireTime;
}
