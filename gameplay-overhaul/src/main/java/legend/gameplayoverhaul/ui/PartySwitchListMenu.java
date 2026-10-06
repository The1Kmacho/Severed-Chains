package legend.gameplayoverhaul.ui;

import legend.core.GameEngine;
import legend.game.characters.CharacterData2c;
import legend.game.characters.VitalsStat;
import legend.game.combat.Battle;
import legend.game.combat.PartySwitchPreparation;
import legend.game.combat.bent.BattleEntity27c;
import legend.game.combat.bent.PlayerBattleEntity;
import legend.game.combat.effects.EffectManagerData6c;
import legend.game.combat.effects.EffectManagerParams;
import legend.game.combat.effects.GenericAttachment1c;
import legend.game.combat.particles.ParticleEffectData98;
import legend.game.combat.ui.BattleHud;
import legend.game.combat.ui.ListMenu;
import legend.game.combat.ui.ListPosition;
import legend.game.i18n.I18n;
import legend.game.inventory.screens.FontOptions;
import legend.game.inventory.screens.HorizontalAlign;
import legend.game.inventory.screens.TextColour;
import legend.game.scripting.RunningScript;
import legend.game.scripting.ScriptState;
import legend.game.textures.TextureAtlasIcon;
import legend.game.ui.UiBox;
import legend.gameplayoverhaul.battleactions.SwitchPartyBattleAction;
import legend.lodmod.LodMod;

import java.util.ArrayList;
import java.util.List;

import static legend.core.GameEngine.CONFIG;
import static legend.game.Scus94491BpeSegment_800b.gameState_800babc8;
import static legend.game.Text.renderText;
import static legend.game.sound.Audio.playMenuSound;
import static legend.game.sound.Audio.stopMenuSound;
import static legend.lodmod.LodConfig.UI_BACKGROUND_COLOUR;

public final class PartySwitchListMenu extends ListMenu {
  private static final int RUN_ANIMATION = 4;
  private static final int RUN_TICKS = 20;
  private static final int RUN_DELAY_TICKS = 10;
  private static final float ESCAPE_DISTANCE = 0x7d0;

  private final List<Integer> characterIds;
  private final List<TextureAtlasIcon> portraits = new ArrayList<>();
  private final FontOptions font = new FontOptions().colour(TextColour.WHITE).size(0.67f);
  private UiBox description;

  private PartySwitchPreparation preparation;
  private boolean transitionStarted;
  private int phase;
  private int phaseTicks;
  private ParticleEffectData98 dust;

  public PartySwitchListMenu(final BattleHud hud, final PlayerBattleEntity activePlayer, final List<Integer> characterIds) {
    super(hud, activePlayer, 250, new ListPosition(), () -> hud.listMenu_800c6b60 = null);
    this.characterIds = List.copyOf(characterIds);

    for(final int charId : this.characterIds) {
      this.portraits.add(GameEngine.getTextureAtlas().getIcon(gameState_800babc8.charData_32c.get(charId).template.getRegistryId()));
    }
  }

  @Override
  protected int getListCount() {
    return this.characterIds.size();
  }

  @Override
  protected void drawListEntry(final int index, final int x, final int y, final int trim) {
    final int charId = this.characterIds.get(index);
    final CharacterData2c character = gameState_800babc8.charData_32c.get(charId);
    final PlayerBattleEntity cached = this.hud.battle.getBattlePlayerForCharacter(charId);
    final boolean eligible = SwitchPartyBattleAction.isEligibleReplacement(this.hud.battle, this.player_08, charId);

    final VitalsStat hp = cached != null
      ? cached.stats.getStat(LodMod.HP_STAT.get())
      : character.stats.getStat(LodMod.HP_STAT.get());
    final VitalsStat mp = cached != null
      ? cached.stats.getStat(LodMod.MP_STAT.get())
      : character.stats.getStat(LodMod.MP_STAT.get());

    this.transforms.identity();
    this.transforms.transfer.set(x, y - 1, 124.2f);
    this.transforms.scaling(12.0f, 14.0f, 1.0f);
    this.portraits.get(index).render(this.transforms)
      .monochrome(eligible ? 1.0f : 0.45f)
      .scissor(x, y, 12, 12);

    this.font.trim(trim).horizontalAlign(HorizontalAlign.LEFT).colour(eligible ? TextColour.WHITE : TextColour.GREY);
    renderText(character.getName().get(), x + 17, y, this.font);
    renderText("HP " + hp.getCurrent() + "/" + hp.getMax(), x + 76, y, this.font);
    renderText("MP " + mp.getCurrent() + "/" + mp.getMax(), x + 154, y, this.font);
    renderText(eligible ? "D" + character.dlevel_13 : SwitchPartyBattleAction.disabledReason(this.hud.battle, charId), x + 218, y, this.font);
  }

  @Override
  protected void onSelection(final int index) {
    final int charId = this.characterIds.get(index);
    if(!SwitchPartyBattleAction.isEligibleReplacement(this.hud.battle, this.player_08, charId)) {
      this.preparation = null;
      return;
    }

    try {
      this.preparation = this.hud.battle.preparePartySwitch(this.player_08, charId);
    } catch(final RuntimeException ex) {
      this.preparation = null;
    }
  }

  @Override
  protected boolean canUse() {
    return this.preparation != null && !this.preparation.hasFailed();
  }

  @Override
  protected void onUse(final int index) {
    this.transitionStarted = true;
    this.phase = 0;
    this.phaseTicks = 0;
  }

  @Override protected void onClose() { }
  @Override protected int handleTargeting() { return 2; }
  @Override public void getTargetingInfo(final RunningScript<?> script) { }

  @Override
  public void tick() {
    if(this.menuState_00 == 10) {
      this.tickTransition();
      return;
    }

    super.tick();

    // A confirmed Switch owns the paused player script until the run-in completes.
    if(this.transitionStarted && this.menuState_00 == 9) {
      this.menuState_00 = 10;
      this.flags_02 = 0;
      this.hud.battleMenu_800c6c34.state_00 = 0;
    }
  }

  private void tickTransition() {
    final Battle battle = this.hud.battle;

    switch(this.phase) {
      case 0 -> {
        if(this.preparation.hasFailed()) {
          this.player_08.getState().setFlag(BattleEntity27c.FLAG_RELOAD_BATTLE_ACTIONS);
          this.transitionStarted = false;
          this.menuState_00 = 9;
          super.tick();
          return;
        }

        if(!this.preparation.isReady()) {
          return;
        }

        battle.setBattleEntityAnimation(this.preparation.outgoingState, RUN_ANIMATION);
        this.preparation.outgoingState.clearFlag(BattleEntity27c.FLAG_ANIMATE_ONCE);
        this.preparation.outgoing.model_148.coord2_14.transforms.rotate.y = legend.core.MathHelper.psxDegToRad(0xc00);
        this.phaseTicks = RUN_DELAY_TICKS;
        this.phase = 1;
      }

      case 1 -> {
        if(--this.phaseTicks > 0) {
          return;
        }

        this.startRunEffects(this.preparation.outgoingState, this.preparation.outgoing, -8.0f);
        battle.moveBattleEntityRelativeToSelf(this.preparation.outgoingState, ESCAPE_DISTANCE, 0.0f, 0.0f, RUN_TICKS);
        this.phaseTicks = 0;
        this.phase = 2;
      }

      case 2 -> {
        this.tickRunEffects();
        if(battle.isBattleEntityMoving(this.preparation.outgoing)) {
          return;
        }

        this.stopRunEffects();
        battle.commitPartySwitchHandoff(this.preparation);

        battle.setBattleEntityAnimation(this.preparation.incomingState, RUN_ANIMATION);
        this.preparation.incomingState.clearFlag(BattleEntity27c.FLAG_ANIMATE_ONCE);
        this.preparation.incoming.model_148.coord2_14.transforms.rotate.y = legend.core.MathHelper.psxDegToRad(0x400);

        this.startRunEffects(this.preparation.incomingState, this.preparation.incoming, 8.0f);
        battle.moveBattleEntityTo(this.preparation.incomingState, this.preparation.formationPosition, RUN_TICKS);
        this.phaseTicks = 0;
        this.phase = 3;
      }

      case 3 -> {
        this.tickRunEffects();
        if(battle.isBattleEntityMoving(this.preparation.incoming)) {
          return;
        }

        this.stopRunEffects();
        battle.finishPartySwitch(this.preparation);
        this.transitionStarted = false;
        this.menuState_00 = 9;
        super.tick();
      }

      default -> throw new IllegalStateException("Invalid party switch phase " + this.phase);
    }
  }

  private void startRunEffects(final ScriptState<PlayerBattleEntity> actorState, final PlayerBattleEntity actor, final float accelerationX) {
    // Exact successful-Escape sound cadence and particle payload.
    playMenuSound(0x20, 0, 3);

    final ScriptState<EffectManagerData6c<EffectManagerParams.ParticleType>> dustState =
      this.hud.battle.particles.allocateParticle(actorState, 3, 8, 0xfff03, 0xc8, 3, 0x100, 0x4124000, actor);

    final EffectManagerData6c<EffectManagerParams.ParticleType> manager = dustState.innerStruct_00;
    manager.params_10.trans_04.zero();
    manager.params_10.scale_16.set(0.25f, 0.25f, 0.25f);
    manager.params_10.colour_1c.set(0x4f, 0x45, 0x38);
    manager.params_10.set24(0, 1);

    this.dust = (ParticleEffectData98)manager.effect_44;
    this.dust.scaleOrUseEffectAcceleration_6c = true;
    this.dust.effectAcceleration_70.set(accelerationX, 0.0f, 0.0f);
    this.dust.scaleParticleAcceleration_80 = 1.0f;

    final GenericAttachment1c lifespan = manager.addAttachment(0, 0, legend.game.combat.SEffe::tickLifespanAttachment, new GenericAttachment1c());
    lifespan.ticksRemaining_1a = 0x12;
  }

  private void tickRunEffects() {
    this.phaseTicks++;
    if(this.phaseTicks == 0x10 && this.dust != null) {
      this.dust.scaleOrUseEffectAcceleration_6c = false;
    }
    if(this.phaseTicks == 0x12) {
      stopMenuSound(0x20, 3);
    }
  }

  private void stopRunEffects() {
    stopMenuSound(0x20, 3);
    if(this.dust != null) {
      this.dust.scaleOrUseEffectAcceleration_6c = false;
      this.dust = null;
    }
  }

  @Override
  public void draw() {
    super.draw();

    if(this.transitionStarted) {
      if(this.phase == 0 && !this.preparation.isReady()) {
        this.font.trim(0).horizontalAlign(HorizontalAlign.CENTRE).colour(TextColour.WHITE);
        renderText("Preparing switch...", 160, 112, this.font);
      }
      return;
    }

    if(this.menuState_00 == 0 || (this.flags_02 & 0x1) == 0) {
      return;
    }

    final int index = this.listScroll_1e + this.listIndex_24;
    if(index < 0 || index >= this.characterIds.size()) {
      return;
    }

    final CharacterData2c character = gameState_800babc8.charData_32c.get(this.characterIds.get(index));
    if(this.description == null) {
      this.description = new UiBox(10, 150, 300, 26);
    }

    this.description.render(CONFIG.getConfig(UI_BACKGROUND_COLOUR.get()));
    this.font.trim(0).horizontalAlign(HorizontalAlign.CENTRE).colour(TextColour.WHITE);
    renderText(I18n.translate(character.getElement()) + "  D'Lv " + character.dlevel_13, 160, 151, this.font);

    final String addition;
    if(character.selectedAddition_19 != null && GameEngine.REGISTRIES.additions.getEntry(character.selectedAddition_19).isValid()) {
      addition = GameEngine.REGISTRIES.additions.getEntry(character.selectedAddition_19).get().getName();
    } else {
      addition = "No Addition";
    }
    renderText(addition, 160, 162, this.font);
  }
}
