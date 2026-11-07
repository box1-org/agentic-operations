package org.box1.aiops;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Identifier validation against the box1-ops-contracts patterns. Nothing reaches an upstream unvalidated. */
public final class Ids {
    private static final Pattern ORDER = Pattern.compile("^ord_[A-Za-z0-9_-]{1,124}$");
    private static final Pattern INCIDENT = Pattern.compile("^inc_(ord_[A-Za-z0-9_-]{1,124})$");
    private static final Pattern ASSET = Pattern.compile("^asset_[A-Za-z0-9_-]{1,122}$");
    private static final Pattern ANALYSIS = Pattern.compile("^ana_[0-9a-f]{32}$");
    private static final Pattern FULFILLMENT = Pattern.compile("^ful_[A-Za-z0-9_-]{1,124}$");
    private static final Pattern RESERVATION = Pattern.compile("^res_[A-Za-z0-9_-]{1,124}$");

    /** Services whose {@code /api/info} the deployed-revision evidence may read. */
    public static final Set<String> SERVICES = Set.of("order-service", "inventory-service", "fulfillment-adapter");

    private Ids() {
    }

    public static String order(String value) {
        return require(ORDER, value, "orderId", "ord_<id>");
    }

    /** Returns the order ID inside a console incident ID {@code inc_<orderId>}. */
    public static String orderOfIncident(String value) {
        Matcher m = value == null ? null : INCIDENT.matcher(value);
        if (m == null || !m.matches()) {
            throw new InvalidArgument("INVALID_INCIDENT_ID", "incidentId must be inc_ followed by an order ID");
        }
        return m.group(1);
    }

    public static String asset(String value) {
        return require(ASSET, value, "assetId", "asset_<id>");
    }

    public static String analysis(String value) {
        return require(ANALYSIS, value, "analysisId", "ana_<32 hex>");
    }

    public static boolean isFulfillment(String value) {
        return value != null && FULFILLMENT.matcher(value).matches();
    }

    public static boolean isReservation(String value) {
        return value != null && RESERVATION.matcher(value).matches();
    }

    public static String service(String value) {
        if (value == null || !SERVICES.contains(value)) {
            throw new InvalidArgument("INVALID_SERVICE", "service must be one of " + SERVICES);
        }
        return value;
    }

    /** Either kind of window key. */
    public static String windowKey(String value) {
        if (value != null && (ORDER.matcher(value).matches() || ASSET.matcher(value).matches())) {
            return value;
        }
        throw new InvalidArgument("INVALID_KEY", "key must be an order ID (ord_...) or an asset ID (asset_...)");
    }

    private static String require(Pattern pattern, String value, String name, String shape) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw new InvalidArgument("INVALID_" + name.toUpperCase(), name + " must match " + shape);
        }
        return value;
    }
}
