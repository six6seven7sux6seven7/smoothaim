package com.example.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
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
    static KeyBinding toggleKey, menuKey;

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

        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.smoothaim.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_G, "category.smoothaim"));
        menuKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.smoothaim.menu", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_ALT, "category.smoothaim"));

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (toggleKey.wasPressed()) {
                c.enabled = !c.enabled;
                save();
                if (mc.player != null)
                    mc.player.sendMessage(Text.literal("SmoothAim: " + (c.enabled ? "ВКЛ" : "ВЫКЛ")), true);
            }
            while (menuKey.wasPressed()) {
                if (mc.currentScreen == null) mc.setScreen(new AimScreen());
            }

            ClientPlayerEntity p = mc.player;
            if (!c.enabled || p == null || mc.world == null || mc.currentScreen != null) return;
            if (!mc.options.attackKey.isPressed()) return;

            Vec3d eye = p.getEyePos();
            Vec3d look = p.getRotationVec(1.0f);
            double bestAngle = c.fov, bestYaw = 0, bestPitch = 0;
            boolean found = false;

            for (LivingEntity e : mc.world.getEntitiesByClass(LivingEntity.class,
                    p.getBoundingBox().expand(c.range + 1),
                    x -> x != p && x.isAlive() && (c.mobs || x instanceof PlayerEntity))) {
                if (p.distanceTo(e) > c.range) continue;
                Box box = e.getBoundingBox();

                if (box.raycast(eye, eye.add(look.multiply(c.range))).isPresent()) continue;

                Vec3d best = null;
                double bestA = Double.MAX_VALUE;
                for (int ix = 0; ix <= 4; ix++)
                for (int iy = 0; iy <= 6; iy++)
                for (int iz = 0; iz <= 4; iz++) {
                    Vec3d pt = new Vec3d(
                        MathHelper.lerp(ix / 4.0, box.minX, box.maxX),
                        MathHelper.lerp(iy / 6.0, box.minY, box.maxY),
                        MathHelper.lerp(iz / 4.0, box.minZ, box.maxZ));
                    double a = angleTo(p, eye, pt)[2];
                    if (a < bestA) { bestA = a; best = pt; }
                }
                Vec3d target = best.lerp(box.getCenter(), c.accuracy);
                double[] r = angleTo(p, eye, target);
                if (r[2] < bestAngle) {
                    bestAngle = r[2]; bestYaw = r[0]; bestPitch = r[1]; found = true;
                }
            }
            if (!found) return;

            p.setYaw(p.getYaw() + (float) MathHelper.clamp(bestYaw * c.smooth, -c.maxStep, c.maxStep));
            p.setPitch(MathHelper.clamp(
                p.getPitch() + (float) MathHelper.clamp(bestPitch * c.smooth, -c.maxStep, c.maxStep),
                -90f, 90f));
        });
    }

    static double[] angleTo(ClientPlayerEntity p, Vec3d eye, Vec3d t) {
        Vec3d d = t.subtract(eye);
        double yaw = Math.toDegrees(Math.atan2(-d.x, d.z));
        double pitch = -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z)));
        double dy = MathHelper.wrapDegrees(yaw - p.getYaw());
        double dp = pitch - p.getPitch();
        return new double[]{dy, dp, Math.hypot(dy, dp)};
    }

    static class AimScreen extends Screen {
        AimScreen() { super(Text.literal("SmoothAim")); }

        @Override
        protected void init() {
            int w = 200, x = width / 2 - w / 2, y = height / 2 - 90;
            addDrawableChild(new Opt(x, y,       w, "Скорость",   0.02, 0.5, c.smooth,   v -> c.smooth = v));
            addDrawableChild(new Opt(x, y + 24,  w, "Точность",   0, 1,      c.accuracy, v -> c.accuracy = v));
            addDrawableChild(new Opt(x, y + 48,  w, "Дальность",  2, 6,      c.range,    v -> c.range = v));
            addDrawableChild(new Opt(x, y + 72,  w, "Угол (FOV)", 5, 120,    c.fov,      v -> c.fov = v));
            addDrawableChild(new Opt(x, y + 96,  w, "Макс. шаг",  1, 20,     c.maxStep,  v -> c.maxStep = v));
            addDrawableChild(ButtonWidget.builder(label(), b -> {
                c.mobs = !c.mobs; b.setMessage(label());
            }).dimensions(x, y + 124, w, 20).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("Закрыть"),
                b -> close()).dimensions(x, y + 150, w, 20).build());
        }

        Text label() {
            return Text.literal("Цели: " + (c.mobs ? "игроки + мобы" : "только игроки"));
        }

        @Override
        public void removed() { save(); }
    }

    static class Opt extends SliderWidget {
        final String name; final double min, max; final Consumer<Double> set;
        Opt(int x, int y, int w, String name, double min, double max, double cur, Consumer<Double> set) {
            super(x, y, w, 20, Text.empty(), MathHelper.clamp((cur - min) / (max - min), 0, 1));
            this.name = name; this.min = min; this.max = max; this.set = set;
            updateMessage();
        }
        double val() { return min + (max - min) * value; }
        @Override protected void updateMessage() {
            setMessage(Text.literal(name + ": " + String.format("%.2f", val())));
        }
        @Override protected void applyValue() { set.accept(val()); }
    }
										}
