package com.example.aquaflow.dto;

import lombok.Data;

import java.util.List;

@Data
public class BatchCreateDTO {

    private List<Integer> orderIds;

}