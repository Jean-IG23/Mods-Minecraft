package jeanig.talarapida;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Set;

public class Tala_rapida implements ModInitializer {
	public static final String MOD_ID = "tala_rapida";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	// Nuestra "Sala de Espera" global. Aquí guardaremos los bloques que deben romperse.
	private static final Queue<Runnable> TAREAS_PENDIENTES = new LinkedList<>();

	@Override
	public void onInitialize() {
		LOGGER.info("Iniciando mod de Tala Rapida (Modo Cascada)!");

		// 1. Escuchador de cuando rompes un bloque
		PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
			if (world.isClient() || player.isCreative()) return true;

			boolean esTronco = state.isIn(BlockTags.LOGS);
			boolean tieneHacha = player.getMainHandStack().isIn(ItemTags.AXES);
			boolean estaAgachado = player.isSneaking();

			if (esTronco && tieneHacha && estaAgachado) {
				escanearArbol(world, pos, player);
			}
			return true;
		});

		// 2. Escuchador del Reloj del Servidor (Se ejecuta 20 veces por segundo)
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			int bloquesPorTick = 4; // Romper 4 bloques cada tick = 80 bloques por segundo

			for (int i = 0; i < bloquesPorTick; i++) {
				if (!TAREAS_PENDIENTES.isEmpty()) {
					TAREAS_PENDIENTES.poll().run(); // Ejecuta la destrucción del bloque
				} else {
					break; // Si ya no hay bloques en espera, detenemos el bucle
				}
			}
		});
	}

	private void escanearArbol(World world, BlockPos posInicial, PlayerEntity player) {
		// Aumentamos los límites para cubrir árboles de jungla colosales
		int limiteTroncos = 400;
		int limiteHojas = 900;

		Queue<BlockPos> porRevisar = new LinkedList<>();
		Set<BlockPos> visitados = new HashSet<>();
		Set<BlockPos> troncosEncontrados = new HashSet<>();

		porRevisar.add(posInicial);
		visitados.add(posInicial);

		// FASE 1: Rastrear toda la madera (No importa qué tan ancha o alta sea)
		while (!porRevisar.isEmpty() && troncosEncontrados.size() < limiteTroncos) {
			BlockPos actual = porRevisar.poll();
			troncosEncontrados.add(actual);

			for (int x = -1; x <= 1; x++) {
				for (int y = -1; y <= 1; y++) {
					for (int z = -1; z <= 1; z++) {
						BlockPos vecino = actual.add(x, y, z);
						if (!visitados.contains(vecino)) {
							BlockState estado = world.getBlockState(vecino);
							if (estado.isIn(BlockTags.LOGS)) {
								porRevisar.add(vecino);
								visitados.add(vecino);
							}
						}
					}
				}
			}
		}

		// FASE 2: Expansión topológica por "Gradiente de Distancia"
		java.util.Map<BlockPos, Integer> distanciasBFS = new java.util.HashMap<>();
		Queue<BlockPos> colaHojas = new LinkedList<>();
		List<BlockPos> hojasEncontradas = new ArrayList<>();

		// Nuestra madera es el punto de partida (Distancia 0)
		for (BlockPos tronco : troncosEncontrados) {
			distanciasBFS.put(tronco, 0);
			colaHojas.add(tronco);
		}

		while (!colaHojas.isEmpty() && hojasEncontradas.size() < limiteHojas) {
			BlockPos actual = colaHojas.poll();
			int misPasos = distanciasBFS.get(actual);

			// Minecraft por defecto no mantiene hojas vivas más allá de 7 bloques de la madera
			if (misPasos >= 7) continue;

			for (int x = -1; x <= 1; x++) {
				for (int y = -1; y <= 1; y++) {
					for (int z = -1; z <= 1; z++) {
						BlockPos vecino = actual.add(x, y, z);

						if (!distanciasBFS.containsKey(vecino)) {
							BlockState estado = world.getBlockState(vecino);

							// Validar que sea hoja natural (no puesta por un jugador)
							if (estado.isIn(BlockTags.LEAVES) && !estado.get(net.minecraft.state.property.Properties.PERSISTENT)) {

								int distanciaRealMC = estado.get(net.minecraft.state.property.Properties.DISTANCE_1_7);
								int nuevosPasos = misPasos + 1;

								// MAGIA: Comparamos nuestros pasos con la distancia interna del juego.
								// Si la distancia real cae de golpe, estamos invadiendo el árbol vecino.
								if (distanciaRealMC >= nuevosPasos) {
									distanciasBFS.put(vecino, nuevosPasos);
									colaHojas.add(vecino);
									hojasEncontradas.add(vecino);
								}
							}
						}
					}
				}
			}
		}

		// FASE 3: Ordenar de Arriba hacia Abajo para la cascada visual
		List<BlockPos> troncosOrdenados = new ArrayList<>(troncosEncontrados);
		troncosOrdenados.sort((p1, p2) -> Integer.compare(p2.getY(), p1.getY()));
		hojasEncontradas.sort((p1, p2) -> Integer.compare(p2.getY(), p1.getY()));

		ItemStack hacha = player.getMainHandStack();

		// FASE 4: Encolar la destrucción
		for (BlockPos tronco : troncosOrdenados) {
			if (tronco.equals(posInicial)) continue;

			TAREAS_PENDIENTES.add(() -> {
				if (hacha.isEmpty()) return;
				world.breakBlock(tronco, true, player);
				hacha.damage(1, player, (p) -> p.sendToolBreakStatus(Hand.MAIN_HAND));
			});
		}

		for (BlockPos hoja : hojasEncontradas) {
			TAREAS_PENDIENTES.add(() -> {
				world.breakBlock(hoja, true, player);
			});
		}
	}
}