package caeruleusTait.world.preview.backend.color;

import com.google.gson.JsonElement;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.JsonOps;

public class JsonElementCodec {
    public static final Codec<JsonElement> CODEC = Codec.PASSTHROUGH.flatXmap(
        dynamic -> {
            Object value = dynamic.getValue();
            if (value instanceof JsonElement jsonElement) {
                return DataResult.success(jsonElement);
            }
            return DataResult.error(() -> "Not a JsonElement: " + value);
        },
        jsonElement -> DataResult.success(new Dynamic<>(JsonOps.INSTANCE, jsonElement))
    );
}
