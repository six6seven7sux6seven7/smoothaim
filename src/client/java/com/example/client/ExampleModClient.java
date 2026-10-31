package com.example.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

public class ExampleModClient implements ClientModInitializer {
    static class Cfg {
        double range = 4.0, fov = 50, smooth = 0.12, maxStep = 6, accuracy = 0.0;
        boolean enabled = false, mobs = false;
    }

    static Cfg c = new Cfg();
    static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    static KeyMapping toggleKey, menuKey;

    static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("smoothaim.json");
    }

    static void save() {
        try { Files.writeString(file(), GSON.toJson(c)); } catch (Exception ignored) {}
    }

    static void load() {
        try {
            if (Files.exists(file())) {
                Cfg r = GSON.fromJson(Files.readString(file()), Cfg.class);
                if (r != null) c = r;
            }
        } catch (Exception ignored) {}
    }

    @Override
    public void onInitializeClient() {
        load();

        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
            "key.smoothaim.toggle", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G, "category.smoothaim"));
        menuKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
            "key.smoothaim.menu", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_ALT, "category.smoothaim"));

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (toggleKey.consumeClick()) {
                c.enabled = !c.enabled;
                save();
                if (mc.player != null)
                    mc.player.displayClientMessage(
                        Component.literal("SmoothAim: " + (c.enabled ? "ВКЛ" : "ВЫКЛ")), true);
            }
            while (menuKey.consumeClick()) {
                if (mc.screen == null) mc.setScreen(new AimScreen());
            }

            LocalPlayer p = mc.player;
            if (!c.enabled || p == null || mc.level == null || mc.screen != null) return;
            if (!mc.options.keyAttack.isDown()) return;

            Vec3 eye = p.getEyePosition();
            Vec3 look = p.getLookAngle();
            double bestAngle = c.fov, bestYaw = 0, bestPitch = 0;
            boolean found = false;

            for (LivingEntity e : mc.level.getEntitiesOfClass(LivingEntity.class,
                    p.getBoundingBox().inflate(c.range + 1),
                    x -> x != p && x.isAlive() && (c.mobs || x instanceof Player))) {
                if (p.distanceTo(e) > c.range) continue;
                AABB box = e.getBoundingBox();

                if (box.clip(eye, eye.add(look.scale(c.range))).isPresent()) continue;

                Vec3 best = null;
                double bestA = Double.MAX_VALUE;
                for (int ix = 0; ix <= 4; ix++)
                for (int iy = 0; iy <= 6; iy++)
                for (int iz = 0; iz <= 4; iz++) {
                    Vec3 pt = new Vec3(
                        Mth.lerp(ix / 4.0, box.minX, box.maxX),
                        Mth.lerp(iy / 6.0, box.minY, box.maxY),
                        Mth.lerp(iz / 4.0, box.minZ, box.maxZ));
                    double a = angleTo(p, eye, pt)[2];
                    if (a < bestA) { bestA = a; best = pt; }
                }
                Vec3 target = best.lerp(box.getCenter(), c.accuracy);
                double[] r = angleTo(p, eye, target);
                if (r[2] < bestAngle) {
                    bestAngle = r[2]; bestYaw = r[0]; bestPitch = r[1]; found = true;
                }
            }
            if (!found) return;

            p.setYRot(p.getYRot() + (float) Mth.clamp(bestYaw * c.smooth, -c.maxStep, c.maxStep));
            p.setXRot(Mth.clamp(
                p.getXRot() + (float) Mth.clamp(bestPitch * c.smooth, -c.maxStep, c.maxStep),
                -90f, 90f));
        });
    }

    static double[] angleTo(LocalPlayer p, Vec3 eye, Vec3 t) {
        Vec3 d = t.subtract(eye);
        double yaw = Math.toDegrees(Math.atan2(-d.x, d.z));
        double pitch = -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z)));
        double dy = Mth.wrapDegrees(yaw - p.getYRot());
        double dp = pitch - p.getXRot();
        return new double[]{dy, dp, Math.hypot(dy, dp)};
    }

    static class AimScreen extends Screen {
        AimScreen() { super(Component.literal("SmoothAim")); }

        @Override
        protected void init() {
            int w = 200, x = width / 2 - w / 2, y = height / 2 - 90;
            addRenderableWidget(new Opt(x, y,       w, "Скорость",   0.02, 0.5, c.smooth,   v -> c.smooth = v));
            addRenderableWidget(new Opt(x, y + 24,  w, "Точность",   0, 1,      c.accuracy, v -> c.accuracy = v));
            addRenderableWidget(new Opt(x, y + 48,  w, "Дальность",  2, 6,      c.range,    v -> c.range = v));
            addRenderableWidget(new Opt(x, y + 72,  w, "Угол (FOV)", 5, 120,    c.fov,      v -> c.fov = v));
            addRenderableWidget(new Opt(x, y + 96,  w, "Макс. шаг",  1, 20,     c.maxStep,  v -> c.maxStep = v));
            addRenderableWidget(Button.builder(label(), b -> {
                c.mobs = !c.mobs; b.setMessage(label());
            }).bounds(x, y + 124, w, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Закрыть"),
                b -> onClose()).bounds(x, y + 150, w, 20).build());
        }

        Component label() {
            return Component.literal("Цели: " + (c.mobs ? "игроки + мобы" : "только игроки"));
        }

        @Override
        public void removed() { save(); }
    }

    static class Opt extends AbstractSliderButton {
        final String name; final double min, max; final Consumer<Double> set;
        Opt(int x, int y, int w, String name, double min, double max, double cur, Consumer<Double> set) {
            super(x, y, w, 20, Component.empty(), Mth.clamp((cur - min) / (max - min), 0, 1));
            this.name = name; this.min = min; this.max = max; this.set = set;
            updateMessage();
        }
        double val() { return min + (max - min) * value; }
        @Override protected void updateMessage() {
            setMessage(Component.literal(name + ": " + String.format("%.2f", val())));
        }
        @Override protected void applyValue() { set.accept(val()); }
    }
}
