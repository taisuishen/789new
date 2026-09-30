package com.bingo789.user.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.ZoneId;
import java.util.List;

/**
 * Licence conditions enforced by user-service.
 *
 * @param minAge                minimum age in full years at registration
 * @param allowedCountries      ISO 3166-1 alpha-2 countries of residence accepted at registration
 * @param allowedCurrencies     ISO 4217 currencies permitted by the licence
 * @param kycRequiredForPlay    game launch requires KYC VERIFIED
 * @param kycRequiredForDeposit deposits require KYC VERIFIED (withdrawals always do)
 * @param timeZone              zone that defines "today" for the age check
 */
@ConfigurationProperties("bingo.compliance")
public record ComplianceProperties(
        @DefaultValue("21") int minAge,
        @DefaultValue("PH") List<String> allowedCountries,
        @DefaultValue("PHP") List<String> allowedCurrencies,
        @DefaultValue("true") boolean kycRequiredForPlay,
        @DefaultValue("false") boolean kycRequiredForDeposit,
        @DefaultValue("+08:00") String timeZone) {

    public boolean isCountryAllowed(String countryCode) {
        return countryCode != null && allowedCountries.stream().anyMatch(countryCode::equalsIgnoreCase);
    }

    public boolean isCurrencyAllowed(String currency) {
        return currency != null && allowedCurrencies.stream().anyMatch(currency::equalsIgnoreCase);
    }

    public ZoneId zone() {
        return ZoneId.of(timeZone);
    }
}
