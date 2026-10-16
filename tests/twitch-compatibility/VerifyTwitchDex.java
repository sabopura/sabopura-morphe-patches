import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.reference.*;
import java.io.File;
import java.util.*;

class VerifyTwitchDex {
    public static void main(String[] args) throws Exception {
        String root = "Lio/github/sabopura/morphe/extension/twitch/";
        var dex = DexFileFactory.loadDexContainer(new File(args[0]), Opcodes.getDefault());
        Map<String,ClassDef> classes = new HashMap<>();
        for (String name : dex.getDexEntryNames())
            for (ClassDef cls : dex.getEntry(name).getDexFile().getClasses()) classes.put(cls.getType(), cls);
        Map<String,Integer> hooks = new TreeMap<>();
        for (ClassDef cls : classes.values()) {
            if (cls.getType().startsWith("Ltv/orange/")) throw new AssertionError("Original mod classes found");
            if (cls.getType().startsWith(root)) continue;
            for (Method method : cls.getMethods()) {
                if (method.getImplementation() == null) continue;
                for (Instruction instruction : method.getImplementation().getInstructions()) {
                    if (instruction instanceof ReferenceInstruction reference
                            && reference.getReference() instanceof MethodReference target
                            && target.getDefiningClass().startsWith(root)) {
                        String key = target.getDefiningClass().substring(root.length()) + "->" + target.getName();
                        hooks.merge(key, 1, Integer::sum);
                    }
                }
            }
        }
        for (var hook : hooks.entrySet()) System.out.println("HOOK " + hook);
        require(hooks.getOrDefault("chat/ChatEnhancements;->bind", 0) > 0, "live row returns present (checked against original below)");
        require(hooks.getOrDefault("chat/ChatEnhancements;->bindVod", 0) == 1, "VOD row return");
        for (String name : List.of("initialize", "onPlay", "onRelease", "onVod", "commentTime"))
            require(hooks.getOrDefault("player/PlayerTools;->" + name, 0) >= 1, "player hook " + name);
        require(hooks.getOrDefault("player/LiveProxy;->rewrite", 0) >= 1, "manifest hook");
        for (String name : List.of("chatWidth", "miniNativeSize"))
            require(hooks.getOrDefault("player/LayoutTools;->" + name, 0) == 1, "layout hook " + name);
        for (String name : List.of("filter", "fullCards"))
            require(hooks.getOrDefault("chat/FeedFilters;->" + name, 0) == 1, "following hook " + name);
        require(hooks.getOrDefault("points/AutoClaimPoints;->onViewState", 0) == 1, "points eligibility wrapper");
        require(hooks.getOrDefault("notifications/FirebaseHeaders;->add", 0) == 1, "scoped Firebase header");
        int bridges = 0;
        for (String bridge : List.of("chat/RowAccess;", "chat/VodRowAccess;", "chat/FeedAccess;", "points/PointsAccess;")) {
            ClassDef cls = classes.get(root + bridge);
            require(cls != null, bridge);
            for (Method method : cls.getMethods()) {
                if (method.getName().equals("<init>")) continue;
                boolean found = false;
                for (Instruction instruction : method.getImplementation().getInstructions()) {
                    if ((method.getName().equals("isHeader") || method.getName().equals("isEnabled")) && instruction.getOpcode() == Opcode.INSTANCE_OF) {
                        found = true;
                        System.out.println("BRIDGE " + method.getName() + " => typed instance-of");
                    }
                    if (instruction.getOpcode() == Opcode.IGET_OBJECT && instruction instanceof ReferenceInstruction r
                            && r.getReference() instanceof FieldReference field) {
                        found = true;
                        ClassDef owner = classes.get(field.getDefiningClass());
                        boolean accessible = false;
                        for (Field f : owner.getFields()) if (f.getName().equals(field.getName()) && f.getType().equals(field.getType()))
                            accessible = AccessFlags.PUBLIC.isSet(f.getAccessFlags());
                        require(accessible, "public bridge field " + field);
                        System.out.println("BRIDGE " + method.getName() + " => " + field);
                    }
                }
                require(found, "unreplaced bridge " + method);
                if (bridge.equals("points/PointsAccess;") && method.getName().equals("claimBonus")) {
                    int calls = 0;
                    for (Instruction i : method.getImplementation().getInstructions())
                        if (i.getOpcode() == Opcode.INVOKE_INTERFACE && i instanceof ReferenceInstruction r
                                && r.getReference() instanceof MethodReference target) {
                            require(target.getReturnType().equals("V") && target.getParameterTypes().size() == 2
                                    && target.getParameterTypes().get(0).toString().equals("Ljava/lang/String;")
                                    && target.getParameterTypes().get(1).toString().equals("Ltv/twitch/android/shared/one/chat/pub/ChatModeMetadata;"), "native bonus signature");
                            ClassDef owner = classes.get(target.getDefiningClass());
                            require(owner != null && AccessFlags.PUBLIC.isSet(owner.getAccessFlags())
                                    && AccessFlags.INTERFACE.isSet(owner.getAccessFlags()), "native public points interface");
                            calls++;
                        }
                    require(calls == 1, "one native bonus submission");
                }
                bridges++;
            }
        }
        require(bridges == 22, "22 typed access bridges");
        verifyLatestContracts(classes, args[2], root);
        int loginMethods = 0;
        boolean loginExpected = args.length > 1 && args[1].equals("login");
        for (ClassDef cls : classes.values()) for (Method method : cls.getMethods()) {
            if (method.getDefiningClass().startsWith(root) || method.getImplementation() == null) continue;
            var code = new ArrayList<Instruction>();
            method.getImplementation().getInstructions().forEach(code::add);
            boolean setup = code.stream().anyMatch(i -> i instanceof ReferenceInstruction r &&
                r.getReference() instanceof StringReference s && s.getString().equals("play_integrity_setup_begin"));
            if (!setup) continue;
            loginMethods++;
            boolean skipped = code.get(0).getOpcode() == Opcode.SGET_OBJECT && code.get(1).getOpcode() == Opcode.RETURN_OBJECT;
            require(skipped == loginExpected, "optional login selection");
            if (skipped) {
                FieldReference singleton = (FieldReference)((ReferenceInstruction)code.get(0)).getReference();
                ClassDef unit = classes.get(singleton.getType());
                boolean nativeUnit = false;
                for (Method m : unit.getMethods()) if (m.getImplementation() != null)
                    for (Instruction i : m.getImplementation().getInstructions())
                        if (i instanceof ReferenceInstruction r && r.getReference() instanceof StringReference s && s.getString().equals("kotlin.Unit")) nativeUnit = true;
                require(nativeUnit, "native Kotlin Unit completion");
            }
        }
        require(loginMethods == 1, "single attestation setup");
        System.out.println("LOGIN optionalEnabled=" + loginExpected);
        // Hide options: Twitch's chat highlight filter asks the extension about additions right
        // after its cast, returns FALSE for a dropped event, and otherwise goes on with its own
        // next instruction (never back into the hook, which would loop forever).
        int highlightHooks = 0;
        for (ClassDef cls : classes.values()) for (Method method : cls.getMethods()) {
            if (method.getDefiningClass().startsWith(root) || method.getImplementation() == null) continue;
            var code = new ArrayList<Instruction>();
            method.getImplementation().getInstructions().forEach(code::add);
            for (int i = 0; i < code.size(); i++) {
                if (!(code.get(i) instanceof ReferenceInstruction r) || !r.getReference().toString().equals(
                        root + "chat/ViewCustomization;->keepHighlight(Ljava/lang/Object;)Z")) continue;
                require(i >= 5 && code.get(i - 5).getOpcode() == Opcode.CHECK_CAST
                        && code.get(i - 4).getOpcode() == Opcode.INSTANCE_OF && code.get(i - 3).getOpcode() == Opcode.IF_NEZ
                        && code.get(i - 2).getOpcode() == Opcode.INSTANCE_OF && code.get(i - 1).getOpcode() == Opcode.IF_EQZ
                        && code.get(i + 1).getOpcode() == Opcode.MOVE_RESULT && code.get(i + 2).getOpcode() == Opcode.IF_NEZ
                        && code.get(i + 3).getOpcode() == Opcode.SGET_OBJECT && code.get(i + 4).getOpcode() == Opcode.RETURN_OBJECT,
                        "highlight filter hook shape");
                require(target(code, i - 3) == i && target(code, i - 1) == i + 5 && target(code, i + 2) == i + 5,
                        "highlight filter hook continues with Twitch's code (" + target(code, i - 3) + ", "
                                + target(code, i - 1) + ", " + target(code, i + 2) + " for " + i + ")");
                require(code.stream().anyMatch(x -> x instanceof ReferenceInstruction v
                        && v.getReference().toString().equals("Ljava/lang/Boolean;->valueOf(Z)Ljava/lang/Boolean;")), "hook sits in the filter");
                highlightHooks++;
            }
        }
        require(highlightHooks == 1, "one community highlight filter hook");
        System.out.println("HIGHLIGHTS filterHooks=" + highlightHooks);
        // Hide the unfollow heart: the player's button list goes through the extension first.
        int buttonHooks = 0;
        for (ClassDef cls : classes.values()) for (Method method : cls.getMethods()) {
            if (!method.getName().equals("<init>") || method.getImplementation() == null) continue;
            Instruction first = method.getImplementation().getInstructions().iterator().next();
            if (first instanceof ReferenceInstruction r && r.getReference().toString().equals(
                    root + "chat/ViewCustomization;->metadataButtons(Ljava/util/List;)V")) buttonHooks++;
        }
        require(buttonHooks == 1, "one player button list hook");
        System.out.println("BUTTONS listHooks=" + buttonHooks);
        System.out.println("PASS dexFiles=" + dex.getDexEntryNames().size() + " classes=" + classes.size() + " bridges=" + bridges);
    }
    /** The index a branch at [index] jumps to. */
    static int target(List<Instruction> code, int index) {
        int[] offsets = new int[code.size()];
        for (int i = 1; i < code.size(); i++) offsets[i] = offsets[i - 1] + code.get(i - 1).getCodeUnits();
        int to = offsets[index] + ((OffsetInstruction) code.get(index)).getCodeOffset();
        for (int i = 0; i < code.size(); i++) if (offsets[i] == to) return i;
        return -1;
    }

    static List<Instruction> code(Method method) {
        var result = new ArrayList<Instruction>();
        method.getImplementation().getInstructions().forEach(result::add);
        return result;
    }
    static List<String> normalized(Method method) {
        var instructions = code(method);
        var indices = new HashMap<Integer,Integer>();
        int address = 0;
        for (int n = 0; n < instructions.size(); n++) {
            indices.put(address, n); address += instructions.get(n).getCodeUnits();
        }
        indices.put(address, instructions.size());
        var result = new ArrayList<String>();
        address = 0;
        for (Instruction i : instructions) {
            String s = i.getOpcode() == Opcode.CONST_STRING_JUMBO ? "CONST_STRING" : i.getOpcode().toString();
            if (i instanceof ThreeRegisterInstruction r) s += ":" + r.getRegisterA()+","+r.getRegisterB()+","+r.getRegisterC();
            else if (i instanceof TwoRegisterInstruction r) s += ":"+r.getRegisterA()+","+r.getRegisterB();
            else if (i instanceof OneRegisterInstruction r) s += ":"+r.getRegisterA();
            if (i instanceof FiveRegisterInstruction r) {
                int[] registers = {r.getRegisterC(),r.getRegisterD(),r.getRegisterE(),r.getRegisterF(),r.getRegisterG()};
                s += ":" + Arrays.toString(Arrays.copyOf(registers, r.getRegisterCount()));
            }
            if (i instanceof RegisterRangeInstruction r) s += ":"+r.getStartRegister()+".."+r.getRegisterCount();
            if (i instanceof WideLiteralInstruction l) s += ":literal="+l.getWideLiteral();
            if (i instanceof ReferenceInstruction r) s += ":ref="+r.getReference();
            if (i instanceof OffsetInstruction b) s += ":target="+indices.get(address+b.getCodeOffset());
            require(!(i instanceof SwitchPayload) && !(i instanceof com.android.tools.smali.dexlib2.iface.instruction.formats.ArrayPayload), "extend comparison for payload");
            result.add(s); address += i.getCodeUnits();
        }
        for (TryBlock<? extends ExceptionHandler> block : method.getImplementation().getTryBlocks()) {
            String s = "TRY:"+indices.get(block.getStartCodeAddress())+".."+indices.get(block.getStartCodeAddress()+block.getCodeUnitCount());
            for (ExceptionHandler handler : block.getExceptionHandlers()) s += ":"+handler.getExceptionType()+"@"+indices.get(handler.getHandlerCodeAddress());
            result.add(s);
        }
        return result;
    }
    static void verifyLatestContracts(Map<String,ClassDef> classes, String originalPath, String root) throws Exception {
        var original = DexFileFactory.loadDexContainer(new File(originalPath), Opcodes.getDefault());
        Map<String,ClassDef> originalClasses = new HashMap<>();
        for (String name : original.getDexEntryNames())
            for (ClassDef cls : original.getEntry(name).getDexFile().getClasses()) originalClasses.put(cls.getType(),cls);
        int wrappers = 0, widths = 0, keys = 0, rows = 0;
        for (ClassDef cls : classes.values()) for (Method method : cls.getMethods()) {
            if (method.getDefiningClass().startsWith(root) || method.getImplementation() == null) continue;
            var instructions = code(method);
            for (int index = 0; index < instructions.size(); index++) {
                Instruction i = instructions.get(index);
                if (!(i instanceof ReferenceInstruction r && r.getReference() instanceof MethodReference target)) continue;
                if (target.getDefiningClass().equals(root+"chat/ChatEnhancements;") && target.getName().equals("bind")) {
                    rows++;
                    Method before=null;
                    for(Method m : originalClasses.get(cls.getType()).getMethods())
                        if(m.getName().equals(method.getName()) && m.getParameterTypes().equals(method.getParameterTypes())) before=m;
                    require(before!=null,"native chat binder exists");
                    long returns=code(before).stream().filter(x -> x.getOpcode()==Opcode.RETURN_VOID).count();
                    long callbacks=instructions.stream().filter(x -> x instanceof ReferenceInstruction ref && ref.getReference().equals(target)).count();
                    require(returns==callbacks && instructions.get(index+1).getOpcode()==Opcode.RETURN_VOID,"every native chat return has one callback");
                    int first=before.getImplementation().getRegisterCount()-3;
                    for(Instruction x : code(before)) if(x.getOpcode().setsRegister() && x instanceof OneRegisterInstruction reg)
                        require(reg.getRegisterA()<first && (!x.getOpcode().setsWideRegister() || reg.getRegisterA()+1<first),"native chat callback params remain intact");
                }
                if (target.getDefiningClass().equals(root+"points/AutoClaimPoints;") && target.getName().equals("onViewState")) {
                    wrappers++;
                    require(instructions.size() == 4 && method.getImplementation().getRegisterCount() == 4, "minimal points wrapper");
                    require(instructions.get(0).getOpcode()==Opcode.INVOKE_DIRECT && index==2, "native renderer runs before callback");
                    var render = (FiveRegisterInstruction)instructions.get(0);
                    var callback = (FiveRegisterInstruction)i;
                    require(render.getRegisterC()==2 && render.getRegisterD()==3 && callback.getRegisterC()==0 && callback.getRegisterD()==2, "receiver and native result isolated from scratch registers");
                    require(instructions.get(1).getOpcode()==Opcode.MOVE_RESULT_OBJECT && ((OneRegisterInstruction)instructions.get(1)).getRegisterA()==0 &&
                            instructions.get(3).getOpcode()==Opcode.RETURN_OBJECT && ((OneRegisterInstruction)instructions.get(3)).getRegisterA()==0, "unaltered native result returned");
                    var nativeRef = (MethodReference)((ReferenceInstruction)instructions.get(0)).getReference();
                    Method helper = null, before = null;
                    for (Method m : cls.getMethods()) if (m.getName().equals(nativeRef.getName())) helper=m;
                    for (Method m : originalClasses.get(cls.getType()).getMethods())
                        if (m.getName().equals(method.getName()) && m.getParameterTypes().equals(method.getParameterTypes()) && m.getReturnType().equals(method.getReturnType())) before=m;
                    require(helper!=null && before!=null && AccessFlags.PRIVATE.isSet(helper.getAccessFlags()), "private native renderer retained");
                    require(helper.getImplementation().getRegisterCount()==before.getImplementation().getRegisterCount() && normalized(helper).equals(normalized(before)), "native eligibility body and branches preserved");
                }
                if (target.getDefiningClass().equals(root+"player/LayoutTools;") && target.getName().equals("chatWidth")) {
                    widths++;
                    var parameters = method.getParameterTypes();
                    int start = method.getImplementation().getRegisterCount()-2;
                    int view = start+parameters.indexOf("Landroid/view/View;"), percent=start+parameters.indexOf("I");
                    require(AccessFlags.STATIC.isSet(method.getAccessFlags()) && parameters.size()==2, "width helper signature");
                    if (i instanceof FiveRegisterInstruction f) require(f.getRegisterC()==view && f.getRegisterD()==percent, "reordered width registers");
                    else { var range=(RegisterRangeInstruction)i; require(range.getStartRegister()==view && range.getRegisterCount()==2 && percent==view+1,"width range registers"); }
                    require(instructions.get(index+1).getOpcode()==Opcode.MOVE_RESULT && ((OneRegisterInstruction)instructions.get(index+1)).getRegisterA()==percent, "width writes percentage parameter");
                }
                if (target.getDefiningClass().equals(root+"chat/ChatEnhancements;") && target.getName().equals("onChannelChanged")) {
                    keys++;
                    require(method.getDefiningClass().endsWith("/ChannelChatConnectionKey;") && method.getName().equals("<init>"), "channel key callback");
                    boolean typed=method.getParameterTypes().get(0).toString().endsWith("/Tuid;");
                    boolean converted=instructions.stream().anyMatch(x -> x instanceof ReferenceInstruction ref && ref.getReference().toString().equals("Ltv/twitch/android/models/Tuid;->toString()Ljava/lang/String;"));
                    require(typed==converted,"channel ID conversion matches version");
                }
            }
        }
        Method userId=null;
        for (Method m : classes.get(root+"chat/RowAccess;").getMethods()) if(m.getName().equals("userId")) userId=m;
        var userCode=code(userId);
        FieldReference id=(FieldReference)((ReferenceInstruction)userCode.get(1)).getReference();
        boolean typed=id.getType().endsWith("/Tuid;");
        require(!typed || (userCode.get(2).getOpcode()==Opcode.IF_EQZ && ((ReferenceInstruction)userCode.get(3)).getReference().toString().equals("Ltv/twitch/android/models/Tuid;->toString()Ljava/lang/String;")), "nullable author Tuid conversion");
        if (typed) {
            // A null branch must not merge a Tuid register with the converted String.
            // Class loading and package compile alone can miss this lazy ART VerifyError.
            require(userCode.size()==8 && userCode.get(4).getOpcode()==Opcode.MOVE_RESULT_OBJECT
                && userCode.get(5).getOpcode()==Opcode.RETURN_OBJECT
                && userCode.get(6).getOpcode()==Opcode.CONST_4
                && ((NarrowLiteralInstruction)userCode.get(6)).getNarrowLiteral()==0
                && userCode.get(7).getOpcode()==Opcode.RETURN_OBJECT, "separate String and explicit null return paths");
            int distance=0;
            for(int n=2;n<6;n++) distance+=userCode.get(n).getCodeUnits();
            require(((OffsetInstruction)userCode.get(2)).getCodeOffset()==distance, "null branch targets explicit zero");
            int register=((OneRegisterInstruction)userCode.get(2)).getRegisterA();
            for(int n=4;n<8;n++) require(((OneRegisterInstruction)userCode.get(n)).getRegisterA()==register,"author return register");
        }
        require(wrappers==1 && widths==1 && keys==1 && rows>0,"unique latest compatibility hooks");
        System.out.println("CONTRACTS nativeEligibilityPreserved=true widthArgumentsCorrect=true typedChannelAndAuthorIds="+typed);
    }
    static void require(boolean condition, String detail) { if (!condition) throw new AssertionError(detail); }
}
