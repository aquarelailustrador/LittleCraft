package com.littlecraft;

import java.util.ArrayList;
import java.util.List;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

public class LittleCraft implements ModInitializer {
	public static final String MOD_ID = "littlecraft";

	private static final double ESCALA_MINI = 0.5;
	private static final int RAIO = 8;
	private static final int ALTURA = 7;
	private static final int PASSO = 20;
	private static final int ULTIMO_ANDAR = 10;

	private static final List<Sala> SALAS = new ArrayList<>();
	private static int contador = 0;
	private static int proximoId = 0;

	private static class Sala {
		ServerWorld mundo;
		Box area;
		List<BlockPos> porta = new ArrayList<>();
		int andar;
		int ox;
		int chaoY;
		int oz;
		int estado; // 0 = esperando jogador, 1 = ativa, 2 = concluida
		String tag;
	}

	@Override
	public void onInitialize() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
			dispatcher.register(CommandManager.literal("littlecraft")
				.then(CommandManager.literal("sala")
					.executes(ctx -> iniciar(ctx, 1)))
				.then(CommandManager.literal("andar")
					.then(CommandManager.argument("n", IntegerArgumentType.integer(1, ULTIMO_ANDAR))
						.executes(ctx -> iniciar(ctx, IntegerArgumentType.getInteger(ctx, "n")))))
				.then(CommandManager.literal("mini")
					.executes(ctx -> definirEscala(ctx, ESCALA_MINI)))
				.then(CommandManager.literal("normal")
					.executes(ctx -> definirEscala(ctx, 1.0)))
			)
		);

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			contador++;
			if (contador % 20 != 0) {
				return;
			}
			for (Sala sala : new ArrayList<>(SALAS)) {
				if (sala.estado == 2) {
					continue;
				}
				if (!temJogadorDentro(sala)) {
					continue;
				}
				if (sala.estado == 0) {
					iniciarSala(sala);
					continue;
				}
				String tag = sala.tag;
				List<MobEntity> vivos = sala.mundo.getEntitiesByClass(
					MobEntity.class, sala.area.expand(24),
					e -> e.isAlive() && e.getCommandTags().contains(tag));
				if (vivos.isEmpty()) {
					concluirSala(sala);
				}
			}
			SALAS.removeIf(s -> s.estado == 2);
		});
	}

	// ---------- comandos ----------

	private static int definirEscala(CommandContext<ServerCommandSource> ctx, double escala)
			throws CommandSyntaxException {
		ServerPlayerEntity jogador = ctx.getSource().getPlayerOrThrow();
		aplicarEscala(jogador, escala);
		ctx.getSource().sendFeedback(
			() -> Text.literal(escala < 1.0 ? "Você ficou mini!" : "Tamanho normal."), false);
		return 1;
	}

	private static int iniciar(CommandContext<ServerCommandSource> ctx, int andar)
			throws CommandSyntaxException {
		ServerCommandSource fonte = ctx.getSource();
		ServerPlayerEntity jogador = fonte.getPlayerOrThrow();
		ServerWorld mundo = fonte.getWorld();

		int ox = jogador.getBlockX();
		int oz = jogador.getBlockZ();
		int chaoY = Math.min(jogador.getBlockY() + 40, mundo.getTopY() - 12);

		Sala sala = construirSala(mundo, ox, chaoY, oz, andar);
		SALAS.add(sala);

		aplicarEscala(jogador, ESCALA_MINI);
		jogador.requestTeleport(ox + 0.5, chaoY + 1.0, oz + 5.5);

		fonte.sendFeedback(() -> Text.literal("LittleCraft: começando no andar " + andar + "!"), false);
		return 1;
	}

	private static void aplicarEscala(ServerPlayerEntity jogador, double escala) {
		EntityAttributeInstance attr = jogador.getAttributeInstance(EntityAttributes.SCALE);
		if (attr != null) {
			attr.setBaseValue(escala);
		}
	}

	// ---------- logica dos andares ----------

	private static boolean temJogadorDentro(Sala sala) {
		for (ServerPlayerEntity p : sala.mundo.getPlayers()) {
			if (sala.area.contains(p.getPos())) {
				return true;
			}
		}
		return false;
	}

	private static void avisar(Sala sala, String texto) {
		for (ServerPlayerEntity p : sala.mundo.getPlayers()) {
			if (sala.area.contains(p.getPos())) {
				p.sendMessage(Text.literal(texto), false);
			}
		}
	}

	private static void iniciarSala(Sala s) {
		if (s.andar == ULTIMO_ANDAR) {
			s.estado = 2;
			avisar(s, "Você chegou ao The SIFT! (por enquanto só o visual)");
			return;
		}
		s.estado = 1;
		int dim = dimensao(s.andar);
		int qtd = 3 + ((s.andar - 1) % 3) * 2;
		for (int i = 0; i < qtd; i++) {
			int r = s.mundo.getRandom().nextInt(100);
			MobEntity m = spawnar(s.mundo, tipoMob(dim, r), posicaoAleatoria(s));
			if (m != null) {
				m.addCommandTag(s.tag);
			}
		}
		avisar(s, "Andar " + s.andar + ": derrote todos os mobs para abrir a porta!");
	}

	private static void concluirSala(Sala s) {
		s.estado = 2;
		if (s.andar < ULTIMO_ANDAR) {
			Sala prox = construirSala(s.mundo, s.ox, s.chaoY, s.oz - PASSO, s.andar + 1);
			SALAS.add(prox);
			construirCorredor(s);
		}
		for (BlockPos p : s.porta) {
			s.mundo.setBlockState(p, Blocks.AIR.getDefaultState());
		}
		for (ServerPlayerEntity p : s.mundo.getPlayers()) {
			if (s.area.contains(p.getPos())) {
				p.giveItemStack(recompensa(s.andar));
				if (s.andar == 2 || s.andar == 5) {
					p.giveItemStack(new ItemStack(Items.ARROW, 32));
				}
			}
		}
		avisar(s, "Porta aberta! Você ganhou uma recompensa.");
	}

	private static ItemStack recompensa(int andar) {
		switch (andar) {
			case 1: return new ItemStack(Items.STONE_SWORD);
			case 2: return new ItemStack(Items.BOW);
			case 3: return new ItemStack(Items.IRON_CHESTPLATE);
			case 4: return new ItemStack(Items.IRON_SWORD);
			case 5: return new ItemStack(Items.CROSSBOW);
			case 6: return new ItemStack(Items.SHIELD);
			case 7: return new ItemStack(Items.DIAMOND_SWORD);
			case 8: return new ItemStack(Items.DIAMOND_CHESTPLATE);
			default: return new ItemStack(Items.TRIDENT);
		}
	}

	// 0 = Overworld, 1 = Nether, 2 = The End, 3 = The SIFT
	private static int dimensao(int andar) {
		if (andar <= 3) return 0;
		if (andar <= 6) return 1;
		if (andar <= 9) return 2;
		return 3;
	}

	private static EntityType<? extends MobEntity> tipoMob(int dim, int r) {
		switch (dim) {
			case 0:
				switch (r % 4) {
					case 0: return EntityType.ZOMBIE;
					case 1: return EntityType.SKELETON;
					case 2: return EntityType.SPIDER;
					default: return EntityType.CREEPER;
				}
			case 1:
				switch (r % 4) {
					case 0: return EntityType.BLAZE;
					case 1: return EntityType.WITHER_SKELETON;
					case 2: return EntityType.MAGMA_CUBE;
					default: return EntityType.PIGLIN_BRUTE;
				}
			default:
				switch (r % 3) {
					case 0: return EntityType.ENDERMAN;
					case 1: return EntityType.SHULKER;
					default: return EntityType.ENDERMITE;
				}
		}
	}

	private static BlockPos posicaoAleatoria(Sala s) {
		int dx = s.mundo.getRandom().nextBetween(-6, 6);
		int dz = s.mundo.getRandom().nextBetween(-6, 0);
		return new BlockPos(s.ox + dx, s.chaoY + 1, s.oz + dz);
	}

	private static MobEntity spawnar(ServerWorld mundo, EntityType<? extends MobEntity> tipo, BlockPos pos) {
		MobEntity mob = tipo.spawn(mundo, pos, SpawnReason.COMMAND);
		if (mob != null) {
			mob.setPersistent();
		}
		return mob;
	}

	// ---------- construcao ----------

	private static Block parede(int andar) {
		switch (dimensao(andar)) {
			case 0: return Blocks.STONE_BRICKS;
			case 1: return Blocks.NETHER_BRICKS;
			case 2: return Blocks.END_STONE_BRICKS;
			default: return Blocks.RED_TERRACOTTA;
		}
	}

	private static Block chao(int andar, int dx, int dz) {
		boolean alt = (Math.abs(dx) + Math.abs(dz)) % 2 == 0;
		switch (dimensao(andar)) {
			case 0: return alt ? Blocks.MOSSY_STONE_BRICKS : Blocks.STONE_BRICKS;
			case 1: return alt ? Blocks.RED_NETHER_BRICKS : Blocks.NETHER_BRICKS;
			case 2: return alt ? Blocks.PURPUR_BLOCK : Blocks.END_STONE_BRICKS;
			default: return Blocks.OXIDIZED_COPPER;
		}
	}

	private static Block teto(int andar) {
		return dimensao(andar) == 3 ? Blocks.PINK_STAINED_GLASS : parede(andar);
	}

	private static Block luz(int andar) {
		return dimensao(andar) == 1 ? Blocks.GLOWSTONE : Blocks.SEA_LANTERN;
	}

	private static Block blocoPorta(int andar) {
		switch (dimensao(andar)) {
			case 1: return Blocks.CHISELED_NETHER_BRICKS;
			case 2: return Blocks.PURPUR_PILLAR;
			default: return Blocks.CHISELED_STONE_BRICKS;
		}
	}

	private static Sala construirSala(ServerWorld mundo, int ox, int chaoY, int oz, int andar) {
		Sala sala = new Sala();
		sala.mundo = mundo;
		sala.andar = andar;
		sala.ox = ox;
		sala.chaoY = chaoY;
		sala.oz = oz;
		sala.estado = 0;
		sala.tag = "littlecraft_" + (proximoId++);
		sala.area = new Box(ox - RAIO + 1, chaoY + 1, oz - RAIO + 1,
			ox + RAIO, chaoY + ALTURA, oz + RAIO);

		for (int dx = -RAIO; dx <= RAIO; dx++) {
			for (int dz = -RAIO; dz <= RAIO; dz++) {
				for (int dy = 0; dy <= ALTURA; dy++) {
					boolean borda = Math.abs(dx) == RAIO || Math.abs(dz) == RAIO;
					Block b = Blocks.AIR;
					if (dy == 0) {
						b = chao(andar, dx, dz);
					} else if (dy == ALTURA) {
						b = teto(andar);
					} else if (borda) {
						b = parede(andar);
					}
					mundo.setBlockState(new BlockPos(ox + dx, chaoY + dy, oz + dz), b.getDefaultState());
				}
			}
		}

		int[] cantos = {-6, 6};
		for (int cx : cantos) {
			for (int cz : cantos) {
				mundo.setBlockState(new BlockPos(ox + cx, chaoY + ALTURA - 1, oz + cz),
					luz(andar).getDefaultState());
			}
		}

		if (andar == ULTIMO_ANDAR) {
			int[][] pilares = {{-4, -4}, {4, -4}, {-4, 3}, {4, 3}};
			for (int[] p : pilares) {
				for (int dy = 1; dy <= 5; dy++) {
					mundo.setBlockState(new BlockPos(ox + p[0], chaoY + dy, oz + p[1]),
						Blocks.RED_TERRACOTTA.getDefaultState());
				}
			}
			for (int dy = 1; dy <= 3; dy++) {
				mundo.setBlockState(new BlockPos(ox, chaoY + dy, oz - 2),
					Blocks.SMOOTH_SANDSTONE.getDefaultState());
			}
		}

		if (andar < ULTIMO_ANDAR) {
			for (int dy = 1; dy <= 2; dy++) {
				BlockPos p = new BlockPos(ox, chaoY + dy, oz - RAIO);
				Block b = blocoPorta(andar);
				if (andar == 9) {
					b = dy == 1 ? Blocks.PINK_STAINED_GLASS : Blocks.YELLOW_STAINED_GLASS;
				}
				mundo.setBlockState(p, b.getDefaultState());
				sala.porta.add(p);
			}
			if (andar == 9) {
				for (int dy = 1; dy <= 2; dy++) {
					mundo.setBlockState(new BlockPos(ox - 1, chaoY + dy, oz - RAIO),
						Blocks.SEA_LANTERN.getDefaultState());
					mundo.setBlockState(new BlockPos(ox + 1, chaoY + dy, oz - RAIO),
						Blocks.SEA_LANTERN.getDefaultState());
				}
				mundo.setBlockState(new BlockPos(ox, chaoY + 3, oz - RAIO),
					Blocks.SEA_LANTERN.getDefaultState());
			}
		}
		return sala;
	}

	private static void construirCorredor(Sala s) {
		Block parede = parede(s.andar);
		for (int z = s.oz - RAIO - 1; z >= s.oz - RAIO - 3; z--) {
			for (int dx = -1; dx <= 1; dx++) {
				for (int dy = 0; dy <= 3; dy++) {
					boolean casca = Math.abs(dx) == 1 || dy == 0 || dy == 3;
					Block b = casca ? parede : Blocks.AIR;
					s.mundo.setBlockState(new BlockPos(s.ox + dx, s.chaoY + dy, z), b.getDefaultState());
				}
			}
		}
		int zEntrada = s.oz - PASSO + RAIO;
		for (int dy = 1; dy <= 2; dy++) {
			s.mundo.setBlockState(new BlockPos(s.ox, s.chaoY + dy, zEntrada),
				Blocks.AIR.getDefaultState());
		}
	}
            }
