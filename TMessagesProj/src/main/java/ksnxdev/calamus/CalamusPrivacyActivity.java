package ksnxdev.calamus;

import android.content.Context;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import uz.unnarsx.cherrygram.preferences.BaseCGPreferencesEntry;

public final class CalamusPrivacyActivity extends BaseCGPreferencesEntry {
    @Override protected CharSequence getTitle() { return LocaleController.getString(R.string.PrivacyPolicy); }
    @Override protected String getKey() { return "calamus_privacy"; }
    @Override protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader("Calamus"));
        try (InputStream stream = getContext().getAssets().open("calamus_privacy.txt"); ByteArrayOutputStream text = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];int count;
            while ((count = stream.read(buffer)) != -1) text.write(buffer, 0, count);
            items.add(UItem.asShadow(new String(text.toByteArray(), StandardCharsets.UTF_8)));
        } catch (Exception error) { items.add(UItem.asShadow("@CalamusGram")); }
    }
}
