package com.example.aquaflow.dto;

import lombok.Data;

import java.util.List;

@Data
public class BatchFinishDTO {
    private List<Integer> finishedOrderIds;
    private List<Integer> unfinishedOrderIds;

}
