package me.majhrs16.suite.coretranslator;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Actor.ActorKind;
import me.majhrs16.suite.api.message.Direction;
import me.majhrs16.suite.api.message.Language;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.spi.ActorDirectory;
import me.majhrs16.suite.api.spi.MessageDispatch;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.api.spi.SyncBus;
import me.majhrs16.suite.api.spi.SyncListener;
import me.majhrs16.suite.api.spi.SyncSink;
import me.majhrs16.suite.api.spi.UserLanguageStore;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CoreTranslatorPlaceholders — %cot_* grammar")
class CoreTranslatorPlaceholdersTest {

    private static final UUID ALICE_ID =
        UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Actor ALICE =
        new Actor(ALICE_ID, "Alice", ActorKind.PLAYER, Language.EN, null);

    @Test
    @DisplayName("translate delega en el puerto y conserva el texto con punto y coma")
    void translateDelegatesAndPreservesPayload() {
        AtomicReference<Language> from = new AtomicReference<>();
        AtomicReference<Language> to = new AtomicReference<>();
        CoreTranslatorPlaceholders placeholders = placeholders(
            (text, source, target) -> {
                from.set(source);
                to.set(target);
                return "hola " + text;
            },
            new FakeActors(),
            new FakeLanguages(),
            new RecordingDispatch(),
            new FakeSyncBus());

        String result = placeholders.resolve(ALICE, "translate;en;es;hello; world");

        assertThat(result).isEqualTo("hola hello; world");
        assertThat(from.get()).isEqualTo(Language.EN);
        assertThat(to.get()).isEqualTo(Language.ES);
    }

    @Test
    @DisplayName("translate rechaza un idioma desconocido sin llamar al puerto")
    void translateRejectsUnknownLanguage() {
        CoreTranslatorPlaceholders placeholders = placeholders(
            (text, source, target) -> {
                throw new AssertionError("translation port must not be called");
            },
            new FakeActors(),
            new FakeLanguages(),
            new RecordingDispatch(),
            new FakeSyncBus());

        assertThat(placeholders.resolve(ALICE, "translate;xx;es;hello")).isEmpty();
    }

    @Test
    @DisplayName("lang devuelve el idioma persistido del jugador")
    void langReturnsStoredLanguage() {
        FakeActors actors = new FakeActors();
        actors.add(ALICE);
        FakeLanguages languages = new FakeLanguages();
        languages.save(ALICE_ID, "es");
        CoreTranslatorPlaceholders placeholders = placeholders(
            (text, source, target) -> text,
            actors, languages, new RecordingDispatch(), new FakeSyncBus());

        assertThat(placeholders.resolve(ALICE, "lang;Alice")).isEqualTo("es");
        assertThat(placeholders.resolve(ALICE, "lang;Unknown")).isEmpty();
    }

    @Test
    @DisplayName("set/get guardan variables por jugador y null las borra")
    void setGetVariables() {
        CoreTranslatorPlaceholders placeholders = placeholders(
            (text, source, target) -> text,
            new FakeActors(), new FakeLanguages(), new RecordingDispatch(), new FakeSyncBus());

        assertThat(placeholders.resolve(ALICE, "set;greeting;hello")).isEqualTo("hello");
        assertThat(placeholders.resolve(ALICE, "get;greeting")).isEqualTo("hello");
        assertThat(placeholders.resolve(ALICE, "set;greeting;null")).isEmpty();
        assertThat(placeholders.resolve(ALICE, "get;greeting")).isEmpty();
    }

    @Test
    @DisplayName("new/send/broadcast usan el historial reciente y el dispatcher")
    void newSendAndBroadcast() {
        RecordingDispatch dispatch = new RecordingDispatch();
        CoreTranslatorPlaceholders placeholders = placeholders(
            (text, source, target) -> text,
            new FakeActors(), new FakeLanguages(), dispatch, new FakeSyncBus());

        String id = placeholders.resolve(ALICE, "new");

        assertThat(UUID.fromString(id)).isNotNull();
        assertThat(placeholders.resolve(ALICE, "send;" + id)).isEqualTo("ok");
        assertThat(dispatch.messages).hasSize(1);
        assertThat(dispatch.messages.get(0).id().toString()).isEqualTo(id);
        assertThat(dispatch.messages.get(0).sender()).isEqualTo(ALICE);

        assertThat(placeholders.resolve(ALICE, "broadcast;" + id)).isEqualTo("ok");
        assertThat(dispatch.messages).hasSize(2);
        Message broadcast = dispatch.messages.get(1);
        assertThat(broadcast.direction().kind()).isEqualTo(Direction.Kind.ALL);
        assertThat(broadcast.id().toString()).isNotEqualTo(id);
    }

    @Test
    @DisplayName("clone crea un mensaje nuevo y guarda su UUID en una variable")
    void cloneStoresNewMessageAndVariable() {
        RecordingDispatch dispatch = new RecordingDispatch();
        CoreTranslatorPlaceholders placeholders = placeholders(
            (text, source, target) -> text,
            new FakeActors(), new FakeLanguages(), dispatch, new FakeSyncBus());

        String id = placeholders.resolve(ALICE, "new");
        String clone = placeholders.resolve(ALICE, "clone;" + id + ";copy");

        assertThat(clone).isNotEqualTo(id);
        assertThat(placeholders.resolve(ALICE, "get;copy")).isEqualTo(clone);
        assertThat(placeholders.resolve(ALICE, "send;" + clone)).isEqualTo("ok");
        assertThat(dispatch.messages).hasSize(1);
        assertThat(dispatch.messages.get(0).id().toString()).isEqualTo(clone);
    }

    @Test
    @DisplayName("sendDiscord usa SyncBus y acepta la lista de canales histórica")
    void sendDiscordUsesSyncBus() {
        FakeSyncBus syncBus = new FakeSyncBus();
        CoreTranslatorPlaceholders placeholders = placeholders(
            (text, source, target) -> text,
            new FakeActors(), new FakeLanguages(), new RecordingDispatch(), syncBus);

        String id = placeholders.resolve(ALICE, "new");

        assertThat(placeholders.resolve(ALICE, "sendDiscord;" + id + ";123,456"))
            .isEqualTo("ok");
        assertThat(syncBus.sink).isEqualTo("discord");
        assertThat(syncBus.message.id().toString()).isEqualTo(id);
    }

    @Test
    @DisplayName("un comando desconocido o un identificador vacío devuelven cadena vacía")
    void unknownCommandReturnsEmpty() {
        CoreTranslatorPlaceholders placeholders = placeholders(
            (text, source, target) -> text,
            new FakeActors(), new FakeLanguages(), new RecordingDispatch(), new FakeSyncBus());

        assertThat(placeholders.resolve(ALICE, "unknown;whatever")).isEmpty();
        assertThat(placeholders.resolve(ALICE, "")).isEmpty();
        assertThat(placeholders.resolve(ALICE, null)).isEmpty();
    }

    private static CoreTranslatorPlaceholders placeholders(
            CoreTranslatorPlaceholders.TranslationPort translations,
            FakeActors actors,
            FakeLanguages languages,
            RecordingDispatch dispatch,
            FakeSyncBus syncBus) {
        return new CoreTranslatorPlaceholders(
            translations, languages, actors, dispatch, syncBus, new NoopLogger());
    }

    private static final class FakeActors implements ActorDirectory {

        private final Map<String, Actor> byName = new HashMap<>();
        private final Map<UUID, Actor> byUuid = new HashMap<>();
        private final Actor console = Actor.console("Console", Language.EN);

        void add(Actor actor) {
            byName.put(actor.name(), actor);
            if (actor.uuid() != null) {
                byUuid.put(actor.uuid(), actor);
            }
        }

        @Override
        public List<Actor> onlinePlayers() {
            return List.copyOf(byUuid.values());
        }

        @Override
        public Optional<Actor> byUuid(UUID uuid) {
            return Optional.ofNullable(byUuid.get(uuid));
        }

        @Override
        public Optional<Actor> byName(String name) {
            return Optional.ofNullable(byName.get(name));
        }

        @Override
        public Actor console() {
            return console;
        }
    }

    private static final class FakeLanguages implements UserLanguageStore {

        private final Map<UUID, String> values = new HashMap<>();

        @Override
        public Optional<String> languageOf(UUID uuid) {
            return Optional.ofNullable(values.get(uuid));
        }

        @Override
        public void save(UUID uuid, String value) {
            values.put(uuid, value);
        }
    }

    private static final class RecordingDispatch implements MessageDispatch {

        private final List<Message> messages = new ArrayList<>();

        @Override
        public void dispatch(Message message) {
            messages.add(message);
        }

        @Override
        public me.majhrs16.suite.api.spi.DispatchOutcome dispatchAndReport(Message message) {
            messages.add(message);
            return me.majhrs16.suite.api.spi.DispatchOutcome.none();
        }
    }

    private static final class FakeSyncBus implements SyncBus {

        private String sink;
        private Message message;

        @Override
        public void register(SyncSink sink) {
        }

        @Override
        public boolean unregister(String name) {
            return false;
        }

        @Override
        public Collection<SyncSink> sinks() {
            return List.of();
        }

        @Override
        public Set<String> sinkNames() {
            return Set.of();
        }

        @Override
        public int broadcast(Message message) {
            return 0;
        }

        @Override
        public Map<String, CompletableFuture<Void>> broadcastAsync(Message message) {
            return Map.of();
        }

        @Override
        public boolean sendTo(String sinkName, Message message) {
            this.sink = sinkName;
            this.message = message;
            return true;
        }

        @Override
        public void setInboundListener(SyncListener listener) {
        }

        @Override
        public void start() {
        }

        @Override
        public void stop() {
        }

        @Override
        public boolean isEmpty() {
            return true;
        }

        @Override
        public SyncListener getInboundListener() {
            return null;
        }

        @Override
        public long getTotalBroadcast() {
            return 0;
        }

        @Override
        public long getTotalDropped() {
            return 0;
        }

        @Override
        public long getTotalRetries() {
            return 0;
        }

        @Override
        public long getTotalDeduped() {
            return 0;
        }

        @Override
        public int getGlobalQueueSize() {
            return 0;
        }

        @Override
        public int getGlobalQueueRemaining() {
            return 0;
        }

        @Override
        public void close() {
        }
    }

    private static final class NoopLogger implements PluginLogger {

        @Override
        public void info(String message, Object... args) {
        }

        @Override
        public void warn(String message, Object... args) {
        }

        @Override
        public void error(String message, Object... args) {
        }

        @Override
        public void error(String message, Throwable throwable) {
        }

        @Override
        public void debug(String message, Object... args) {
        }
    }
}
