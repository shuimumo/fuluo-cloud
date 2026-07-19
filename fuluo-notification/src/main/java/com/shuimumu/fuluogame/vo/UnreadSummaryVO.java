package com.shuimumu.fuluogame.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class UnreadSummaryVO {
    private long total;
    private long comment;
    private long like;
    private long follow;
    private long system;
}
