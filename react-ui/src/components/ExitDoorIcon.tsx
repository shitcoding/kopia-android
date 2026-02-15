import React from "react";

interface ExitDoorIconProps {
  className?: string;
}

const ExitDoorIcon: React.FC<ExitDoorIconProps> = ({ className = "w-5 h-5" }) => (
  <svg
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    strokeWidth="2"
    strokeLinecap="round"
    strokeLinejoin="round"
    className={className}
    xmlns="http://www.w3.org/2000/svg"
  >
    {/* Door frame */}
    <path d="M9 3h12v18H9" />
    {/* Arrow */}
    <path d="M14 12H3" />
    <path d="M7 8l-4 4 4 4" />
  </svg>
);

export default ExitDoorIcon;
