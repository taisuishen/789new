package com.bingo789.kyc.domain;

import lombok.Getter;
import lombok.Setter;

/** A row of the config table. */
@Getter
@Setter
public class ConfigEntry {

    private String cfgName;
    private String cfgValue;
}
