package dev.everest8000.radio.client;

/**
 * Implemented by every HumanoidModel (mixin): how far up the radio was in the model's last setupAnim, so the held-item
 * layer drawn right after it places the radio to match the arm.
 */
public interface RadioPosedModel {
    float everestRadio$amount();
}
