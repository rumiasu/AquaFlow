package com.example.aquaflow.dto;

import com.example.aquaflow.entity.Address;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** C-01: null/omission keeps existing values; true flags explicitly clear nullable facts. */
@Data
@EqualsAndHashCode(callSuper = true)
public class AddressProfileDTO extends Address {
    private Boolean clearFloor;
    private Boolean clearElevator;
    private Boolean clearCoordinates;
}
