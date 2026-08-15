package me.majhrs16.cht.core.language;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * Known languages supported by the translation engines.
 *
 * <p>Each constant holds the code understood by the underlying providers
 * ({@code google} tag) and a human readable display name. {@link #AUTO}
 * represents "detect automatically" and is never a real target language.</p>
 */
public enum Language {

    AUTO("auto", "Automático"),
    AF("af", "Afrikaans"),
    SQ("sq", "Shqiptare"),
    AM("am", "አማርኛ"),
    AR("ar", "العربية"),
    HY("hy", "Հայերեն"),
    AS("as", "অসমীয়া"),
    AY("ay", "Aymara"),
    AZ("az", "Azərbaycan"),
    BM("bm", "Bamanankan"),
    EU("eu", "Euskara"),
    BE("be", "Беларускі"),
    BN("bn", "বাংলা"),
    BHO("bho", "भोसपुरी के बा"),
    BS("bs", "Bosanski"),
    BG("bg", "Български"),
    CA("ca", "Català"),
    CEB("ceb", "Cebuano"),
    ZH_CN("zh-CN", "简体中文"),
    ZH("zh-CN", "简体中文"),
    ZH_TW("zh-TW", "繁體中文"),
    CO("co", "Corsu"),
    HR("hr", "Hrvatski"),
    CS("cs", "Čeština"),
    DA("da", "Dansk"),
    DV("dv", "ދިވެހި"),
    DOI("doi", "डोगरी"),
    NL("nl", "Nederlands"),
    EN("en", "English"),
    EO("eo", "Esperanto"),
    ET("et", "Eesti keel"),
    EE("ee", "Eʋegbe"),
    FIL("fil", "Filipino Tagalog"),
    FI("fi", "Suomi"),
    FR("fr", "Français"),
    FY("fy", "Frysk"),
    GL("gl", "Galego"),
    KA("ka", "ქართული"),
    DE("de", "Deutsch"),
    EL("el", "Ελληνικά"),
    GN("gn", "Guarani"),
    GU("gu", "ગુજરાતી"),
    HT("ht", "Kreyòl ayisyen"),
    HA("ha", "Hausa"),
    HAW("haw", "ʻŌlelo Hawaiʻi"),
    HE("he", "עִברִית"),
    IW("iw", "עִברִית"),
    HI("hi", "हिंदी"),
    HMN("hmn", "Hmoob"),
    HU("hu", "Magyar"),
    IS("is", "Íslenskur"),
    IG("ig", "Igbo"),
    ILO("ilo", "Ilocano"),
    ID("id", "Bahasa Indonesia"),
    GA("ga", "Gaeilge"),
    IT("it", "Italiano"),
    JA("ja", "日本語"),
    JV("jv", "Basa jawa"),
    JW("jw", "Basa jawa"),
    KN("kn", "ಕನ್ನಡ"),
    KK("kk", "Қазақ"),
    KM("km", "ខ្មែរ"),
    RW("rw", "Kiñarwanda"),
    GOM("gom", "कोंकणी"),
    KO("ko", "한국어"),
    KRI("kri", "Krio"),
    KU("ku", "Kurdî"),
    CKB("ckb", "کوردی سۆرانی"),
    KY("ky", "Кыргызча"),
    LO("lo", "ລາວ"),
    LA("la", "Latinus"),
    LV("lv", "Latviski"),
    LN("ln", "Lingala"),
    LT("lt", "Lietuvių"),
    LG("lg", "Oluganda"),
    LB("lb", "Lëtzebuergesch"),
    MK("mk", "Македонски"),
    MAI("mai", "मैथिली"),
    MG("mg", "Malagasy"),
    MS("ms", "Melayu"),
    ML("ml", "മലയാളം"),
    MT("mt", "Malti"),
    MI("mi", "Maori"),
    MR("mr", "मराठी"),
    LUS("lus", "Mizo tawng"),
    MN("mn", "Монгол"),
    MY("my", "မြန်မာ"),
    NE("ne", "नेपाली"),
    NO("no", "Norsk"),
    NY("ny", "Nyanja Chichewa"),
    OR("or", "ଓଡ଼ିଆ"),
    OM("om", "Afaan Oromoo"),
    PS("ps", "پښتو"),
    FA("fa", "فارسی"),
    PL("pl", "Polski"),
    PT("pt", "Português"),
    PA("pa", "ਪੰਜਾਬੀ"),
    QU("qu", "Runasimi"),
    RO("ro", "Română"),
    RU("ru", "Русский"),
    SM("sm", "Samoa"),
    SA("sa", "संस्कृत"),
    GD("gd", "Gàidhlig na h-Alba"),
    NSO("nso", "Sepedi"),
    SR("sr", "Српски"),
    ST("st", "Sesotho"),
    SN("sn", "Shona"),
    SD("sd", "سنڌي"),
    SI("si", "සිංහල"),
    SK("sk", "Slovenský"),
    SL("sl", "Slovenščina"),
    SO("so", "Soomaali"),
    ES("es", "Español"),
    SU("su", "Basa sunda"),
    SW("sw", "Kiswahili"),
    SV("sv", "Svenska"),
    TL("tl", "Tagalog Filipino"),
    TG("tg", "Тоҷикӣ"),
    TA("ta", "தமிழ்"),
    TT("tt", "Татар"),
    TE("te", "తెలుగు"),
    TH("th", "แบบไทย"),
    TI("ti", "Tigriña"),
    TS("ts", "Tsonga"),
    TR("tr", "Türkçe"),
    TK("tk", "Türkmenler"),
    AK("ak", "Twi Akan"),
    UK("uk", "Українська"),
    UR("ur", "اردو"),
    UG("ug", "Uigur"),
    UZ("uz", "O'zbek"),
    VI("vi", "Tiếng Việt"),
    CY("cy", "Cymraeg"),
    XH("xh", "IsiXhosa"),
    YI("yi", "ייִדיש"),
    YO("yo", "Yoruba"),
    ZU("zu", "Zulu");

    private final String code;
    private final String displayName;

    Language(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    /** @return the provider code, e.g. {@code zh-CN} or {@code auto}. */
    public String code() {
        return code;
    }

    /** @return a human readable display name in Spanish. */
    public String displayName() {
        return displayName;
    }

    /**
     * Resolves a language by its code (case insensitive).
     * Accepts {@code zh-cn}, {@code zh_cn} and {@code zh-CN} equivalently.
     *
     * @param code the raw code, may be {@code null}.
     */
    public static Optional<Language> fromCode(String code) {
        if (code == null) {
            return Optional.empty();
        }
        String normalized = code.trim().replace('_', '-').toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
            .filter(language -> language != AUTO)
            .filter(language -> language.code.equalsIgnoreCase(normalized))
            .findFirst();
    }

    /**
     * Like {@link #fromCode(String)} but tolerant to unknown codes: resolves
     * {@code A-AUTO} as {@link #AUTO} and any other unknown value as empty.
     */
    public static Optional<Language> of(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String trimmed = raw.trim();
        if (trimmed.equalsIgnoreCase("auto") || trimmed.equalsIgnoreCase("AUTO")
                || trimmed.equalsIgnoreCase("a")) {
            return Optional.of(AUTO);
        }
        return fromCode(trimmed);
    }
}