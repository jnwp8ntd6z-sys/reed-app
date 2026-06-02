export function ReedMascot() {
  return (
    <svg
      width="100"
      height="120"
      viewBox="0 0 100 120"
      fill="none"
      xmlns="http://www.w3.org/2000/svg"
    >
      <defs>
        {/* Main body gradient - 3D orange */}
        <radialGradient id="bodyGradient" cx="0.35" cy="0.35">
          <stop offset="0%" stopColor="#FFB366" />
          <stop offset="50%" stopColor="#FF8C42" />
          <stop offset="100%" stopColor="#FF6B35" />
        </radialGradient>

        {/* Leaf gradient - 3D green */}
        <radialGradient id="leafGradient" cx="0.3" cy="0.3">
          <stop offset="0%" stopColor="#C8E67D" />
          <stop offset="60%" stopColor="#9BD200" />
          <stop offset="100%" stopColor="#7AAD00" />
        </radialGradient>

        {/* Shadow gradient */}
        <radialGradient id="shadowGradient" cx="0.5" cy="0.5">
          <stop offset="0%" stopColor="rgba(0,0,0,0.2)" />
          <stop offset="100%" stopColor="rgba(0,0,0,0)" />
        </radialGradient>

        {/* Eye shine gradient */}
        <radialGradient id="eyeShine" cx="0.3" cy="0.3">
          <stop offset="0%" stopColor="rgba(255,255,255,0.9)" />
          <stop offset="100%" stopColor="rgba(255,255,255,0)" />
        </radialGradient>

        {/* Highlight gradient */}
        <radialGradient id="highlightGradient" cx="0.3" cy="0.2">
          <stop offset="0%" stopColor="rgba(255,220,180,0.8)" />
          <stop offset="100%" stopColor="rgba(255,220,180,0)" />
        </radialGradient>
      </defs>

      {/* Ground shadow - soft */}
      <ellipse cx="50" cy="110" rx="22" ry="5" fill="url(#shadowGradient)" />

      {/* Main body - organic blob shape with 3D effect */}
      <path
        d="M 50 28 C 72 28 78 42 78 58 C 78 74 72 88 50 88 C 28 88 22 74 22 58 C 22 42 28 28 50 28 Z"
        fill="url(#bodyGradient)"
      />

      {/* Body shadow (left side darker) */}
      <ellipse cx="32" cy="60" rx="10" ry="24" fill="rgba(200,70,30,0.15)" />

      {/* Body highlight (top-left) */}
      <ellipse cx="38" cy="45" rx="16" ry="20" fill="url(#highlightGradient)" />

      {/* Leaf on top - single organic shape */}
      <g>
        {/* Leaf shadow */}
        <ellipse cx="50" cy="20" rx="10" ry="8" fill="rgba(100,140,0,0.2)" />

        {/* Main leaf */}
        <path
          d="M 50 8 C 42 10 38 15 38 22 C 38 26 42 29 50 29 C 58 29 62 26 62 22 C 62 15 58 10 50 8 Z"
          fill="url(#leafGradient)"
        />

        {/* Leaf highlight */}
        <ellipse cx="47" cy="18" rx="6" ry="5" fill="rgba(200,255,150,0.5)" />

        {/* Leaf vein */}
        <line x1="50" y1="12" x2="50" y2="26" stroke="#7AAD00" strokeWidth="1.5" strokeLinecap="round" opacity="0.6" />
      </g>

      {/* Eyes - big and glossy like Duolingo */}
      <g>
        {/* Left eye outer */}
        <ellipse cx="38" cy="56" rx="7" ry="8" fill="#2D2D2D" />
        {/* Left eye white */}
        <ellipse cx="38" cy="54" rx="6" ry="7" fill="white" />
        {/* Left pupil */}
        <ellipse cx="38" cy="56" rx="4" ry="4.5" fill="#1A1A1A" />
        {/* Left eye shine - big */}
        <ellipse cx="36" cy="53" rx="2.5" ry="3" fill="white" />
        {/* Left eye shine - small */}
        <circle cx="40" cy="58" r="1.2" fill="rgba(255,255,255,0.7)" />

        {/* Right eye outer */}
        <ellipse cx="62" cy="56" rx="7" ry="8" fill="#2D2D2D" />
        {/* Right eye white */}
        <ellipse cx="62" cy="54" rx="6" ry="7" fill="white" />
        {/* Right pupil */}
        <ellipse cx="62" cy="56" rx="4" ry="4.5" fill="#1A1A1A" />
        {/* Right eye shine - big */}
        <ellipse cx="60" cy="53" rx="2.5" ry="3" fill="white" />
        {/* Right eye shine - small */}
        <circle cx="64" cy="58" r="1.2" fill="rgba(255,255,255,0.7)" />
      </g>

      {/* Smile - thick and cute */}
      <path
        d="M 36 70 Q 50 76 64 70"
        stroke="#2D2D2D"
        strokeWidth="3.5"
        strokeLinecap="round"
        fill="none"
      />
      {/* Smile inner shadow */}
      <path
        d="M 38 71 Q 50 75 62 71"
        stroke="rgba(200,70,30,0.3)"
        strokeWidth="1.5"
        strokeLinecap="round"
        fill="none"
      />

      {/* Arms - chubby and rounded */}
      <g>
        {/* Left arm */}
        <ellipse cx="18" cy="64" rx="8" ry="16" fill="url(#bodyGradient)" transform="rotate(-25 18 64)" />
        <ellipse cx="15" cy="62" rx="3" ry="8" fill="rgba(255,220,180,0.5)" transform="rotate(-25 15 62)" />

        {/* Left hand */}
        <ellipse cx="10" cy="78" rx="6" ry="7" fill="url(#bodyGradient)" />
        <ellipse cx="8" cy="76" rx="2.5" ry="3" fill="rgba(255,220,180,0.6)" />

        {/* Right arm */}
        <ellipse cx="82" cy="64" rx="8" ry="16" fill="url(#bodyGradient)" transform="rotate(25 82 64)" />
        <ellipse cx="85" cy="62" rx="3" ry="8" fill="rgba(255,220,180,0.5)" transform="rotate(25 85 62)" />

        {/* Right hand */}
        <ellipse cx="90" cy="78" rx="6" ry="7" fill="url(#bodyGradient)" />
        <ellipse cx="92" cy="76" rx="2.5" ry="3" fill="rgba(255,220,180,0.6)" />
      </g>

      {/* Legs - short and chunky */}
      <g>
        {/* Left leg */}
        <ellipse cx="38" cy="94" rx="9" ry="14" fill="url(#bodyGradient)" />
        <ellipse cx="36" cy="92" rx="3.5" ry="7" fill="rgba(255,220,180,0.4)" />

        {/* Left foot */}
        <ellipse cx="38" cy="106" rx="10" ry="5" fill="#FF6B35" />
        <ellipse cx="36" cy="105" rx="6" ry="3" fill="rgba(255,180,140,0.5)" />

        {/* Right leg */}
        <ellipse cx="62" cy="94" rx="9" ry="14" fill="url(#bodyGradient)" />
        <ellipse cx="64" cy="92" rx="3.5" ry="7" fill="rgba(255,220,180,0.4)" />

        {/* Right foot */}
        <ellipse cx="62" cy="106" rx="10" ry="5" fill="#FF6B35" />
        <ellipse cx="64" cy="105" rx="6" ry="3" fill="rgba(255,180,140,0.5)" />
      </g>

      {/* Cheeks - rosy and 3D */}
      <ellipse cx="26" cy="65" rx="5" ry="4" fill="#FF9B7A" opacity="0.6" />
      <ellipse cx="24" cy="64" rx="2" ry="2" fill="rgba(255,200,180,0.8)" />

      <ellipse cx="74" cy="65" rx="5" ry="4" fill="#FF9B7A" opacity="0.6" />
      <ellipse cx="76" cy="64" rx="2" ry="2" fill="rgba(255,200,180,0.8)" />
    </svg>
  );
}
