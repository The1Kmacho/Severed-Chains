package legend.gameplayoverhaul;

import legend.core.platform.input.ButtonInputActivation;
import legend.core.platform.input.InputAction;
import legend.core.platform.input.InputActionRegistryEvent;
import legend.core.platform.input.InputButton;
import legend.core.platform.input.InputKey;
import legend.core.platform.input.ScancodeInputActivation;
import legend.game.additions.Addition;
import legend.game.characters.CharacterAdditionInfo;
import legend.game.characters.Element;
import legend.game.characters.UnaryStatModConfig;
import legend.game.characters.VitalsStat;
import legend.game.combat.bent.AttackEvent;
import legend.game.combat.bent.AttackSpecialEffectEvent;
import legend.game.combat.bent.BattleEntity27c;
import legend.game.combat.bent.MonsterBattleEntity;
import legend.game.combat.bent.PlayerBattleEntity;
import legend.game.combat.types.AttackType;
import legend.game.modding.events.characters.AdditionDescriptionEvent;
import legend.game.modding.events.characters.DragoonAdditionCompletedEvent;
import legend.game.modding.events.input.InputPressedEvent;
import legend.game.modding.events.input.RegisterDefaultInputBindingsEvent;
import legend.lodmod.LodAdditions;
import legend.lodmod.LodMod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.legendofdragoon.modloader.Mod;
import org.legendofdragoon.modloader.events.EventListener;
import org.legendofdragoon.modloader.registries.Registrar;
import org.legendofdragoon.modloader.registries.RegistryDelegate;
import org.legendofdragoon.modloader.registries.RegistryId;

import java.util.Set;

import static legend.core.GameEngine.REGISTRIES;
import static legend.game.Scus94491BpeSegment_8006.battleState_8006e398;
import static legend.game.Scus94491BpeSegment_800b.gameState_800babc8;
import static legend.game.Scus94491BpeSegment_800b.tickCount_800bb0fc;

@Mod(id = GameplayOverhaulMod.MOD_ID, version = "^3.0.0")
@EventListener
public final class GameplayOverhaulMod {
  public static final String MOD_ID = "gameplay_overhaul";

  private static final Logger LOGGER = LogManager.getFormatterLogger(GameplayOverhaulMod.class);

  private static final Registrar<InputAction, InputActionRegistryEvent> INPUTS =
    new Registrar<>(REGISTRIES.inputActions, MOD_ID);

  public static final RegistryDelegate<InputAction> INPUT_ACTION_DODGE =
    INPUTS.register("bttl_dodge", InputAction::editable);

  private static final int PARRY_WINDOW_TICKS = 4;
  private static final int DODGE_WINDOW_TICKS = 8;

  private static int lastParryTick = Integer.MIN_VALUE;
  private static int lastDodgeTick = Integer.MIN_VALUE;
  private static int negatedAttackTick = Integer.MIN_VALUE;
  private static BattleEntity27c negatedDefender;

  private static final Set<RegistryId> FINAL_ADDITIONS = Set.of(
    LodAdditions.BLAZING_DYNAMO.getId(),
    LodAdditions.FLOWER_STORM.getId(),
    LodAdditions.ALBERT_FLOWER_STORM.getId(),
    LodAdditions.DEMONS_DANCE.getId(),
    LodAdditions.OMNI_SWEEP.getId(),
    LodAdditions.PERKY_STEP.getId(),
    LodAdditions.BONE_CRUSH.getId()
  );

  public GameplayOverhaulMod() { }

  @EventListener
  public static void registerInputActions(final InputActionRegistryEvent event) {
    INPUTS.registryEvent(event);
  }

  @EventListener
  public static void registerDefaultBindings(final RegisterDefaultInputBindingsEvent event) {
    event
      .add(INPUT_ACTION_DODGE.get(), new ScancodeInputActivation(InputKey.C))
      .add(INPUT_ACTION_DODGE.get(), new ButtonInputActivation(InputButton.X));
  }

  @EventListener
  public static void inputPressed(final InputPressedEvent event) {
    if(event.repeat) {
      return;
    }

    if(event.action == LodMod.INPUT_ACTION_BTTL_COUNTER.get()) {
      lastParryTick = tickCount_800bb0fc;
    } else if(event.action == INPUT_ACTION_DODGE.get()) {
      lastDodgeTick = tickCount_800bb0fc;
    }
  }

  @EventListener
  public static void attack(final AttackEvent event) {
    if(event.attacker instanceof final MonsterBattleEntity && event.defender instanceof final PlayerBattleEntity) {
      final int now = tickCount_800bb0fc;
      final int parryAge = now - lastParryTick;
      final int dodgeAge = now - lastDodgeTick;

      if(parryAge >= 0 && parryAge <= PARRY_WINDOW_TICKS) {
        event.damage = 0;
        lastParryTick = Integer.MIN_VALUE;
        markNegatedAttack(event.defender);
        LOGGER.info("[Gameplay Overhaul] PARRY");
        return;
      }

      if(dodgeAge >= 0 && dodgeAge <= DODGE_WINDOW_TICKS) {
        event.damage = 0;
        lastDodgeTick = Integer.MIN_VALUE;
        markNegatedAttack(event.defender);
        LOGGER.info("[Gameplay Overhaul] DODGE");
        return;
      }
    }

    if(!(event.attacker instanceof final PlayerBattleEntity player)
      || event.attackType != AttackType.PHYSICAL
      || player.isDragoon()) {
      return;
    }

    // Shana and Miranda have no retail additions. Their bow attacks receive the
    // archer-specific gameplay identity directly.
    if(player.charId_272 == 2 || player.charId_272 == 8) {
      addElementalBonus(event, player.getElement(), 20);
      return;
    }

    if(player.addition == null || (battleState_8006e398.additionState_324 & 0x20) == 0) {
      return;
    }

    final int percent = isFinal(player.addition) ? 25 : 10;
    addElementalBonus(event, player.getElement(), percent);
  }

  @EventListener
  public static void specialEffect(final AttackSpecialEffectEvent event) {
    if(event.defender == negatedDefender && tickCount_800bb0fc == negatedAttackTick) {
      event.effect = -1;
      negatedDefender = null;
      negatedAttackTick = Integer.MIN_VALUE;
      return;
    }

    if(!(event.attacker instanceof final PlayerBattleEntity player)
      || event.attackType != AttackType.PHYSICAL
      || player.isDragoon()) {
      return;
    }

    // Archer exception: no retail addition exists, so the regular attack heals.
    if(player.charId_272 == 2 || player.charId_272 == 8) {
      healPercent(player, 10);
      return;
    }

    if(player.addition == null
      || !isFinal(player.addition)
      || (battleState_8006e398.additionState_324 & 0x20) == 0) {
      return;
    }

    final RegistryId id = player.addition.getRegistryId();

    if(id.equals(LodAdditions.BLAZING_DYNAMO.getId())) {
      forceStatusIfAllowed(event, 0x10); // Stun
    } else if(id.equals(LodAdditions.FLOWER_STORM.getId()) || id.equals(LodAdditions.ALBERT_FLOWER_STORM.getId())) {
      player.powerDefence_b8 = 50;
      player.powerDefenceTurns_b9 = 3;
    } else if(id.equals(LodAdditions.DEMONS_DANCE.getId())) {
      forceStatusIfAllowed(event, 0x08); // Fear
    } else if(id.equals(LodAdditions.OMNI_SWEEP.getId())) {
      player.stats.getStat(LodMod.SPEED_STAT.get()).addMod(
        new RegistryId(MOD_ID, "omni_sweep_speed"),
        LodMod.UNARY_STAT_MOD_TYPE.get().make(new UnaryStatModConfig().percent(50).turns(3))
      );
    } else if(id.equals(LodAdditions.PERKY_STEP.getId())) {
      forceStatusIfAllowed(event, 0x10); // Stun/freeze analogue in the retail status set
    } else if(id.equals(LodAdditions.BONE_CRUSH.getId())) {
      player.powerAttack_b4 = 50;
      player.powerAttackTurns_b5 = 3;
    }
  }

  @EventListener
  public static void describeAddition(final AdditionDescriptionEvent event) {
    final RegistryId id = event.addition.getRegistryId();

    if(id.equals(LodAdditions.BLAZING_DYNAMO.getId())) {
      event.description = "Complete: Fire +25%, Stun";
    } else if(id.equals(LodAdditions.FLOWER_STORM.getId()) || id.equals(LodAdditions.ALBERT_FLOWER_STORM.getId())) {
      event.description = "Complete: Wind +25%, Defense Up";
    } else if(id.equals(LodAdditions.DEMONS_DANCE.getId())) {
      event.description = "Complete: Dark +25%, Fear";
    } else if(id.equals(LodAdditions.OMNI_SWEEP.getId())) {
      event.description = "Complete: Thunder +25%, Speed Up";
    } else if(id.equals(LodAdditions.PERKY_STEP.getId())) {
      event.description = "Complete: Water +25%, Stun";
    } else if(id.equals(LodAdditions.BONE_CRUSH.getId())) {
      event.description = "Complete: Earth +25%, Power Up";
    } else {
      event.description = "Complete: innate element +10%";
    }
  }

  @EventListener
  public static void dragoonAdditionCompleted(final DragoonAdditionCompletedEvent event) {
    if(event.successfulCircles <= 0 || event.charId < 0 || event.charId >= gameState_800babc8.charData_32c.size()) {
      return;
    }

    final var character = gameState_800babc8.charData_32c.get(event.charId);
    if(character.selectedAddition_19 == null) {
      return;
    }

    final CharacterAdditionInfo info = character.getAdditionInfo(character.selectedAddition_19);
    if(info != null) {
      info.xp += event.successfulCircles;
      LOGGER.info(
        "[Gameplay Overhaul] Dragoon circles granted %d addition XP to %s",
        event.successfulCircles,
        character.selectedAddition_19
      );
    }
  }

  private static boolean isFinal(final Addition addition) {
    return FINAL_ADDITIONS.contains(addition.getRegistryId());
  }

  private static void markNegatedAttack(final BattleEntity27c defender) {
    negatedDefender = defender;
    negatedAttackTick = tickCount_800bb0fc;
  }

  private static void addElementalBonus(final AttackEvent event, final Element element, final int percent) {
    int bonus = Math.max(1, event.damage * percent / 100);
    bonus = element.adjustAttackingElementalDamage(AttackType.PHYSICAL, bonus, event.defender.getElement());
    bonus = event.defender.getElement().adjustDefendingElementalDamage(AttackType.PHYSICAL, bonus, element);
    bonus = event.defender.applyElementalResistanceAndImmunity(bonus, element);
    event.damage += Math.max(0, bonus);
  }

  private static void healPercent(final PlayerBattleEntity player, final int percent) {
    final VitalsStat hp = player.stats.getStat(LodMod.HP_STAT.get());
    hp.setCurrent(Math.min(hp.getMax(), hp.getCurrent() + Math.max(1, hp.getMax() * percent / 100)));
  }

  private static void forceStatusIfAllowed(final AttackSpecialEffectEvent event, final int status) {
    if((event.defender.specialEffectFlag_14 & 0x80) != 0) {
      return;
    }

    if((event.defender.equipmentStatusResist_24 & status) != 0) {
      return;
    }

    if(event.defender instanceof final MonsterBattleEntity monster && (monster.monsterStatusResistFlag_76 & status) != 0) {
      return;
    }

    event.effect = status;
  }
}
