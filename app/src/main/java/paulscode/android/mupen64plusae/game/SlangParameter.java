package paulscode.android.mupen64plusae.game;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A shader's declared control, including its preset default and the user's current value. */
public final class SlangParameter {
    static final Pattern PRAGMA = Pattern.compile(
            "(?m)^[\\t ]*#pragma\\s+parameter\\s+(\\w+)\\s+\"([^\"]*)\"[\\t ]+(\\S+)[\\t ]+(\\S+)[\\t ]+(\\S+)(?:[\\t ]+([^\\s/]+))?[\\t ]*(?://[^\\n]*)?$"
    );

    public final String id, label;
    public final float minimum, maximum, step, defaultValue, value;

    /** Called by the native preset inspector; defaults include values inherited from referenced presets. */
    public SlangParameter(String id, String label, float initial, float minimum, float maximum, float step) {
        this.id = id;
        this.label = label;
        this.minimum = minimum;
        this.maximum = maximum;
        this.step = step;
        this.defaultValue = this.value = initial;
    }

    private SlangParameter(Matcher match, Map<String, String> preset, Map<String, String> overrides) throws IOException {
        id = match.group(1);
        label = match.group(2);
        minimum = number(match.group(4));
        maximum = number(match.group(5));
        step = match.group(6) == null ? (maximum == minimum ? 1 : (maximum - minimum) / 10) : number(match.group(6));
        if (maximum < minimum || !Float.isFinite(step) || step < 0 || step == 0 && maximum != minimum)
            throw new IOException("Invalid range for parameter " + id);
        defaultValue = checked(preset.getOrDefault(id, match.group(3)));
        value = checked(overrides.getOrDefault(id, Float.toString(defaultValue)));
    }

    /** Validate an entered value and snap it to the declared step, without accumulating float error. */
    public float editedValue(String text) throws IOException {
        BigDecimal entered = new BigDecimal(Float.toString(checked(text)));
        if (maximum == minimum) return minimum;
        if (step <= 0) return entered.floatValue();
        BigDecimal origin = new BigDecimal(Float.toString(minimum));
        BigDecimal increment = new BigDecimal(Float.toString(step));
        BigDecimal steps = entered.subtract(origin).divide(increment, 0, RoundingMode.HALF_UP);
        return Math.max(minimum, Math.min(maximum, origin.add(steps.multiply(increment)).floatValue()));
    }

    private float checked(String text) throws IOException {
        float result = number(text);
        if (result < minimum || result > maximum)
            throw new IOException(id + " must be between " + minimum + " and " + maximum);
        return result;
    }

    private static float number(String text) throws IOException {
        try {
            float value = Float.parseFloat(text.trim());
            if (!Float.isFinite(value)) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException e) { throw new IOException("Invalid parameter value: " + text, e); }
    }

    static List<SlangParameter> parse(String source, Map<String, String> preset, Map<String, String> overrides) throws IOException {
        List<SlangParameter> parameters = new ArrayList<>();
        source = source.replace("\r", "");
        for (String line : source.split("\n"))
            if (line.trim().startsWith("#pragma parameter ") && !PRAGMA.matcher(line).matches())
                throw new IOException("Invalid parameter declaration: " + line.trim());
        Matcher matcher = PRAGMA.matcher(source);
        while (matcher.find()) parameters.add(new SlangParameter(matcher, preset, overrides));
        return parameters;
    }
}
