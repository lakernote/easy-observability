package io.github.lakernote.observability.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesBinding;
import org.springframework.context.annotation.Bean;
import org.springframework.core.convert.converter.Converter;
import org.springframework.util.unit.DataSize;
import org.springframework.util.unit.DataUnit;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Supports familiar IEC suffixes such as 64MiB on configuration DataSize properties. */
@AutoConfiguration
public class DataSizeConfigurationAutoConfiguration {
    @Bean
    @ConfigurationPropertiesBinding
    static Converter<String, DataSize> easyObservabilityDataSizeConverter() {
        return new ReadableDataSizeConverter();
    }

    static final class ReadableDataSizeConverter implements Converter<String, DataSize> {
        private static final Pattern IEC_SIZE = Pattern.compile("^([+-]?\\d+)\\s*(KiB|MiB|GiB|TiB)$",
                Pattern.CASE_INSENSITIVE);

        @Override
        public DataSize convert(String value) {
            String text = value == null ? "" : value.trim();
            Matcher matcher = IEC_SIZE.matcher(text);
            if (!matcher.matches()) return DataSize.parse(text);

            long amount = Long.parseLong(matcher.group(1));
            DataUnit unit = switch (matcher.group(2).toLowerCase(Locale.ROOT)) {
                case "kib" -> DataUnit.KILOBYTES;
                case "mib" -> DataUnit.MEGABYTES;
                case "gib" -> DataUnit.GIGABYTES;
                case "tib" -> DataUnit.TERABYTES;
                default -> throw new IllegalArgumentException("Unsupported IEC data size: " + value);
            };
            return DataSize.of(amount, unit);
        }
    }
}
