package com.example.autolibrarian;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Авто-реролл библиотекаря.
 * Команды:
 *   /librarian set <зачарование> [мин_уровень] [макс_цена]   (например: /librarian set mending 1 20)
 *   /librarian start   (смотри на кафедру, рядом должен стоять библиотекарь без залоченных трейдов)
 *   /librarian stop
 *
 * ВАЖНО: некоторые имена классов/методов в 26.x могли чуть поменяться —
 * если IDE подсветит ошибку, замени на ближайший аналог.
 */
public class AutoLibrarianClient implements ClientModInitializer {

    private enum State { IDLE, OPEN, CHECK, BREAK, PLACE, WAIT }

    private static State state = State.IDLE;
    private static String targetEnchant = "mending";
    private static int minLevel = 1;
    private static int maxPrice = 64;

    private static BlockPos lecternPos;
    private static Villager villager;
    private static int timer = 0;

    @Override
    public void onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("librarian")
                .then(ClientCommandManager.literal("set")
                    .then(ClientCommandManager.argument("enchant", StringArgumentType.word())
                        .executes(ctx -> {
                            targetEnchant = StringArgumentType.getString(ctx, "enchant");
                            minLevel = 1;
                            maxPrice = 64;
                            msg("Цель: " + targetEnchant);
                            return 1;
                        })
                        .then(ClientCommandManager.argument("level", IntegerArgumentType.integer(1, 10))
                            .executes(ctx -> {
                                targetEnchant = StringArgumentType.getString(ctx, "enchant");
                                minLevel = IntegerArgumentType.getInteger(ctx, "level");
                                maxPrice = 64;
                                msg("Цель: " + targetEnchant + " >= " + minLevel);
                                return 1;
                            })
                            .then(ClientCommandManager.argument("price", IntegerArgumentType.integer(1, 64))
                                .executes(ctx -> {
                                    targetEnchant = StringArgumentType.getString(ctx, "enchant");
                                    minLevel = IntegerArgumentType.getInteger(ctx, "level");
                                    maxPrice = IntegerArgumentType.getInteger(ctx, "price");
                                    msg("Цель: " + targetEnchant + " >= " + minLevel + ", цена <= " + maxPrice);
                                    return 1;
                                })))))
                .then(ClientCommandManager.literal("start").executes(ctx -> { start(); return 1; }))
                .then(ClientCommandManager.literal("stop").executes(ctx -> { stop("Остановлено"); return 1; })));
        });

        ClientTickEvents.END_CLIENT_TICK.register(AutoLibrarianClient::tick);
    }

    // ---------- управление ----------

    private static void start() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        // 1. Кафедра под прицелом
        HitResult hit = mc.hitResult;
        if (!(hit instanceof BlockHitResult bhr)
                || !mc.level.getBlockState(bhr.getBlockPos()).is(Blocks.LECTERN)) {
            msg("Смотри на кафедру (lectern)!");
            return;
        }
        lecternPos = bhr.getBlockPos();

        // 2. Ближайший библиотекарь
        villager = null;
        double best = 36;
        for (Villager v : mc.level.getEntitiesOfClass(Villager.class,
                mc.player.getBoundingBox().inflate(6))) {
            if (v.getVillagerData().profession().is(VillagerProfession.LIBRARIAN)) {
                double d = v.distanceToSqr(mc.player);
                if (d < best) { best = d; villager = v; }
            }
        }
        if (villager == null) {
            msg("Библиотекарь рядом не найден.");
            return;
        }

        // 3. Кафедра должна быть в хотбаре (для постановки обратно)
        if (findLecternSlot(mc) < 0) {
            msg("Положи кафедру в хотбар (её нужно будет ставить обратно).");
            return;
        }

        msg("Старт. Ищем: " + targetEnchant + " >= " + minLevel + ", цена <= " + maxPrice);
        state = State.OPEN;
        timer = 0;
    }

    private static void stop(String reason) {
        state = State.IDLE;
        msg(reason);
    }

    // ---------- основной цикл ----------

    private static void tick(Minecraft mc) {
        if (state == State.IDLE || mc.player == null || mc.level == null || mc.gameMode == null) return;
        if (villager == null || villager.isRemoved()) { stop("Житель пропал — стоп."); return; }

        switch (state) {
            case OPEN -> {
                if (mc.screen instanceof MerchantScreen) { state = State.CHECK; timer = 0; return; }
                if (timer++ % 20 == 0) {
                    mc.gameMode.interact(mc.player, villager, InteractionHand.MAIN_HAND);
                }
                if (timer > 200) stop("Не удалось открыть торговлю — стоп.");
            }

            case CHECK -> {
                // даём серверу прислать список трейдов
                if (++timer < 5) return;
                if (!(mc.screen instanceof MerchantScreen screen)) { state = State.OPEN; timer = 0; return; }

                MerchantMenu menu = screen.getMenu();
                if (menu.getOffers().isEmpty()) return;

                for (MerchantOffer offer : menu.getOffers()) {
                    if (matches(offer)) {
                        mc.player.playSound(net.minecraft.sounds.SoundEvents.PLAYER_LEVELUP, 1f, 1f);
                        stop("НАЙДЕНО! Окно торговли оставлено открытым.");
                        return;
                    }
                }
                mc.player.closeContainer();
                state = State.BREAK;
                timer = 0;
            }

            case BREAK -> {
                if (mc.level.getBlockState(lecternPos).isAir()) { state = State.PLACE; timer = 0; return; }
                mc.gameMode.startDestroyBlock(lecternPos, Direction.UP);
                mc.gameMode.continueDestroyBlock(lecternPos, Direction.UP);
                mc.player.swing(InteractionHand.MAIN_HAND);
            }

            case PLACE -> {
                // подождать, пока кафедра-предмет подберётся
                if (++timer < 8) return;
                int slot = findLecternSlot(mc);
                if (slot < 0) { stop("Кафедра пропала из хотбара — стоп."); return; }
                mc.player.getInventory().setSelectedSlot(slot);

                // ставим на блок под кафедрой, грань вверх
                BlockPos below = lecternPos.below();
                Vec3 hitVec = Vec3.atCenterOf(below).add(0, 0.5, 0);
                BlockHitResult place = new BlockHitResult(hitVec, Direction.UP, below, false);
                mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, place);
                mc.player.swing(InteractionHand.MAIN_HAND);

                if (mc.level.getBlockState(lecternPos).is(Blocks.LECTERN)) {
                    state = State.WAIT;
                    timer = 0;
                } else if (timer > 60) {
                    stop("Не получилось поставить кафедру — стоп.");
                }
            }

            case WAIT -> {
                // ждём, пока житель снова возьмёт профессию библиотекаря
                if (villager.getVillagerData().profession().is(VillagerProfession.LIBRARIAN)) {
                    if (++timer >= 10) { state = State.OPEN; timer = 0; }
                } else {
                    timer = 0;
                }
            }

            default -> { }
        }
    }

    // ---------- проверки ----------

    private static boolean matches(MerchantOffer offer) {
        ItemStack result = offer.getResult();
        if (!result.is(Items.ENCHANTED_BOOK)) return false;
        if (offer.getCostA().getCount() > maxPrice) return false;

        ItemEnchantments ench = result.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY);
        for (var entry : ench.entrySet()) {
            String id = entry.getKey().unwrapKey().map(k -> k.identifier().getPath()).orElse("");
            if (id.equals(targetEnchant) && entry.getIntValue() >= minLevel) return true;
        }
        return false;
    }

    private static int findLecternSlot(Minecraft mc) {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getItem(i).is(Items.LECTERN)) return i;
        }
        return -1;
    }

    private static void msg(String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.displayClientMessage(Component.literal("[AutoLibrarian] " + text), false);
    }
}
