import java.io.File;
import app.morphe.patcher.apk.ApkUtils;
class SignProbe {
    public static void main(String[] args) {
        File store=new File(args[2]);
        if(!store.isFile()) throw new IllegalArgumentException("Existing test signing store is required");
        app.morphe.engine.util.KeystoreSignerKt.signWithLegacyFallback(
            new ApkUtils.KeyStoreDetails(store,null,"Morphe","Morphe"),true,
            java.util.logging.Logger.getLogger("DeviceProbe"),details -> {
                ApkUtils.INSTANCE.signApk(new File(args[0]),new File(args[1]),"Morphe",details);
                return kotlin.Unit.INSTANCE;
            });
        System.out.println("Signed diagnostic APK using existing local signer");
    }
}
