package com.lighthlib.client.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.util.ARGB;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.lighthlib.client.render.RenderTintContext;

/**
 * The LHCE color multiply for vanilla single-quad particles (flame, portal,
 * potion, smoke, ...): the quad's vertex color is composed from the
 * particle's rCol/gCol/bCol/alpha inside
 * {@code SingleQuadParticle.extractRotatedQuad}, which every quad particle
 * funnels through when its render state is extracted - so the single hook
 * here covers all particle types.
 *
 * <p>The particle's vertex <i>brightness</i> already picks up the 16-bit
 * boost through {@code Particle.getLightCoords} (which routes through the
 * hooked {@code LightCoordsUtil.getLightCoords}); this adds the matching
 * color tint, resolved at the particle's world position with the same
 * vanilla-dominance gates as the entity path ({@code RenderTintContext}).
 * Particles outside HLib fields keep vanilla colors (identity multiply).</p>
 */
@Mixin(SingleQuadParticle.class)
public abstract class SingleQuadParticleMixin extends Particle {
	// x/y/z are declared on the parent Particle in 26.3; accessing them via
	// plain inheritance (mixin merges into the subclass) instead of @Shadow,
	// which can only locate fields declared on the target class itself.
	@Shadow protected float rCol;
	@Shadow protected float gCol;
	@Shadow protected float bCol;

	protected SingleQuadParticleMixin(ClientLevel level, double x, double y, double z) {
		super(level, x, y, z);
	}

	@Inject(method = "extractRotatedQuad(Lnet/minecraft/client/renderer/state/level/QuadParticleRenderState;"
			+ "Lorg/joml/Quaternionf;FFFF)V",
			at = @At("HEAD"))
	private void lighthlib$tintParticle(QuadParticleRenderState state, Quaternionf rotation,
										float x, float y, float z, float partialTick, CallbackInfo ci) {
		int tint = RenderTintContext.particleTint(this.x, this.y, this.z);
		if (tint == -1) {
			// identity path: nothing was saved, so the RETURN handler must not
			// restore stale/zero saved channels (defaults are 0.0f and would
			// black out every vanilla particle, e.g. torch flames)
			return;
		}
		if (this.lighthlib$tinted) {
			// previous extraction never restored (aborted between the two
			// injections): recover the pre-tint channels FIRST so we don't
			// multiply the tint on top of the already-tinted values
			this.rCol = this.lighthlib$savedRCol;
			this.gCol = this.lighthlib$savedGCol;
			this.bCol = this.lighthlib$savedBCol;
		}
		// render states are extracted every frame and rCol/gCol/bCol persist
		// on the particle across frames: multiplying them in place would
		// compound the tint frame over frame (ever-darkening color). Keep the
		// original channels and restore them once the quad has been built.
		this.lighthlib$savedRCol = this.rCol;
		this.lighthlib$savedGCol = this.gCol;
		this.lighthlib$savedBCol = this.bCol;
		this.lighthlib$tinted = true;
		this.rCol *= ARGB.red(tint) / 255.0F;
		this.gCol *= ARGB.green(tint) / 255.0F;
		this.bCol *= ARGB.blue(tint) / 255.0F;
	}

	@Inject(method = "extractRotatedQuad(Lnet/minecraft/client/renderer/state/level/QuadParticleRenderState;"
			+ "Lorg/joml/Quaternionf;FFFF)V",
			at = @At("RETURN"))
	private void lighthlib$untintParticle(QuadParticleRenderState state, Quaternionf rotation,
										  float x, float y, float z, float partialTick, CallbackInfo ci) {
		if (!this.lighthlib$tinted) {
			// identity pass: HEAD never saved/restored anything - restoring the
			// (zero-initialized) saved channels here would black out vanilla
			// particles in unlit worlds
			return;
		}
		this.lighthlib$tinted = false;
		this.rCol = this.lighthlib$savedRCol;
		this.gCol = this.lighthlib$savedGCol;
		this.bCol = this.lighthlib$savedBCol;
	}

	@Unique
	private float lighthlib$savedRCol;
	@Unique
	private float lighthlib$savedGCol;
	@Unique
	private float lighthlib$savedBCol;
	/** True between the HEAD save and the RETURN restore of one tinted
	 *  extraction; guards the identity path (and any nested/aborted pass)
	 *  against restoring channels that were never saved. */
	@Unique
	private boolean lighthlib$tinted;
}
