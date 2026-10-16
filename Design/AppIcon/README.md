# RunTick Android app icon

Source: the user's Apple Icon Composer archive. `ios-default-1024.png` is byte-for-byte identical to the default icon used by `runtick-ios`; `runtick-symbol.svg` preserves the original mark geometry.

- Legacy launcher PNGs in mdpi through xxxhdpi are resized directly from the iOS default export.
- Android 8+ adaptive icons use the original SVG paths, a matching teal background (#7CE1DD), and matching foreground color (#142D34). The mark is inset into the adaptive icon safe area so circular, squircle and other launcher masks do not clip it. Android supplies the outer shape instead of baking in Apple's rounded corners.
- Android 13+ monochrome icons reuse the same mark's alpha for system themed icons.
- Both standard and round launcher resources are declared in the manifest.

The Apple-specific glass edge is preserved in the legacy PNG export. Adaptive icons use Android's native masking and launcher effects.
