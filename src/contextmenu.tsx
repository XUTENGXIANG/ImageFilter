import * as ContextMenuPrimitive from "@radix-ui/react-context-menu";

export interface MenuItem {
  label: string;
  action?: () => void;
  children?: MenuItem[];
  disabled?: boolean;
}

interface Props {
  items: MenuItem[];
  onOpenChange?: (open: boolean) => void;
  children: React.ReactNode;
}

const itemClass = "group text-[11px] leading-none text-zinc-300 rounded-sm flex items-center h-7 px-2 relative select-none outline-none data-[disabled]:text-zinc-600 data-[disabled]:pointer-events-none data-[highlighted]:bg-zinc-700 data-[highlighted]:text-zinc-100 cursor-pointer";
const contentClass = "min-w-[160px] bg-zinc-800 border border-zinc-700 rounded-lg p-1 shadow-2xl z-[100]";

export function PixelMenu({ items, onOpenChange, children }: Props) {
  return (
    <ContextMenuPrimitive.Root onOpenChange={onOpenChange}>
      <ContextMenuPrimitive.Trigger className="contents">
        {children}
      </ContextMenuPrimitive.Trigger>
      <ContextMenuPrimitive.Portal>
        <ContextMenuPrimitive.Content className={contentClass} alignOffset={-4}>
          <MenuItems items={items} />
        </ContextMenuPrimitive.Content>
      </ContextMenuPrimitive.Portal>
    </ContextMenuPrimitive.Root>
  );
}

function MenuItems({ items }: { items: MenuItem[] }) {
  return items.map((item, i) => {
    if (item.label === "" && !item.action) {
      return <ContextMenuPrimitive.Separator key={i} className="h-px bg-zinc-700 mx-2 my-1" />;
    }
    if (item.children) {
      return (
        <ContextMenuPrimitive.Sub key={i}>
          <ContextMenuPrimitive.SubTrigger className={itemClass}>
            {item.label}
            <span className="ml-auto text-zinc-500">▶</span>
          </ContextMenuPrimitive.SubTrigger>
          <ContextMenuPrimitive.Portal>
            <ContextMenuPrimitive.SubContent className={contentClass} sideOffset={2} alignOffset={-4}>
              <MenuItems items={item.children} />
            </ContextMenuPrimitive.SubContent>
          </ContextMenuPrimitive.Portal>
        </ContextMenuPrimitive.Sub>
      );
    }
    return (
      <ContextMenuPrimitive.Item
        key={i}
        className={itemClass}
        disabled={item.disabled}
        onSelect={item.action}
      >
        {item.label}
      </ContextMenuPrimitive.Item>
    );
  });
}

// Re-export separator items signal
export const SEPARATOR: MenuItem = { label: "", disabled: true };
